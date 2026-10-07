<#
  A real TURN Allocate against one relay, so "is this relay working?" has an answer that does
  not involve a cross-country screen share.

  Sends an unauthenticated Allocate (expecting 401 + REALM/NONCE), then a second one carrying
  USERNAME / REALM / NONCE / MESSAGE-INTEGRITY (HMAC-SHA1 over MD5("user:realm:pass"), per
  RFC 5389/5766 long-term credentials), over plain TCP or over TLS. Prints the allocated
  XOR-RELAYED-ADDRESS on success, or the server's own error code.

  Reading the result:
    ALLOCATE OK ...   the relay works with these credentials, over this transport.
    FAILED err=[401]  the relay is alive and rejected the password. Protocol fine, creds wrong.
    FAILED err=[400]  the relay rejected the request outright - which is what
                      openrelay.metered.ca does for *every* username, valid or not, since its
                      public static credentials were retired.
    ERROR no response  nothing speaks plain TURN on that port (443 is usually TLS-only: retry
                      with -Mode tls).

  Usage:
    ./relay-check.ps1 -RelayHost relay1.expressturn.com -Port 3478 -User u -Pass p -Mode tcp
    ./relay-check.ps1 -RelayHost relay1.expressturn.com -Port 5349 -User u -Pass p -Mode tls
    ./relay-check.ps1 -RelayHost r.example.com -Port 443 -Mode tls -Secret shared-secret
      (-Secret uses the TURN REST API scheme: a timestamp username and an HMAC-SHA1 password.)
#>
param([Parameter(Mandatory=$true)][string]$RelayHost, [int]$Port, [string]$User, [string]$Pass, [string]$Mode = "tcp", [string]$Secret = "")

$ErrorActionPreference = "Stop"

function New-StunMsg([int]$type, [byte[]]$body) {
  $m = New-Object System.Collections.Generic.List[byte]
  $m.Add([byte](($type -shr 8) -band 0xff)); $m.Add([byte]($type -band 0xff))
  $m.Add([byte](($body.Length -shr 8) -band 0xff)); $m.Add([byte]($body.Length -band 0xff))
  $m.AddRange([byte[]]@(0x21,0x12,0xA4,0x42))
  $tid = New-Object byte[] 12
  (New-Object Random).NextBytes($tid)
  $m.AddRange($tid)
  $m.AddRange($body)
  return ,$m.ToArray()
}

function Add-Attr([System.Collections.Generic.List[byte]]$body, [int]$type, [byte[]]$val) {
  $body.Add([byte](($type -shr 8) -band 0xff)); $body.Add([byte]($type -band 0xff))
  $body.Add([byte](($val.Length -shr 8) -band 0xff)); $body.Add([byte]($val.Length -band 0xff))
  $body.AddRange($val)
  while ($body.Count % 4 -ne 0) { $body.Add(0) }
}

function Parse-Attrs([byte[]]$buf) {
  $res = @{}
  $i = 20
  while ($i + 4 -le $buf.Length) {
    $t = ($buf[$i] -shl 8) -bor $buf[$i+1]
    $l = ($buf[$i+2] -shl 8) -bor $buf[$i+3]
    if ($i + 4 + $l -gt $buf.Length) { break }
    $v = New-Object byte[] $l
    [Array]::Copy($buf, $i+4, $v, 0, $l)
    if (-not $res.ContainsKey($t)) { $res[$t] = $v }
    $i += 4 + $l
    while ($i % 4 -ne 0) { $i++ }
  }
  return $res
}

function Get-Stream {
  $tcp = New-Object System.Net.Sockets.TcpClient
  $iar = $tcp.BeginConnect($RelayHost, $Port, $null, $null)
  if (-not $iar.AsyncWaitHandle.WaitOne(6000)) { throw "connect timeout" }
  $tcp.EndConnect($iar)
  $tcp.ReceiveTimeout = 8000; $tcp.SendTimeout = 8000
  if ($Mode -eq "tls") {
    $ssl = New-Object System.Net.Security.SslStream($tcp.GetStream(), $false, ({ $true }))
    $ssl.AuthenticateAsClient($RelayHost)
    return @{ s = $ssl; c = $tcp; proto = $ssl.SslProtocol }
  }
  return @{ s = $tcp.GetStream(); c = $tcp; proto = "none" }
}

function Send-Recv($stream, [byte[]]$msg) {
  $stream.Write($msg, 0, $msg.Length); $stream.Flush()
  $buf = New-Object byte[] 2048
  $n = $stream.Read($buf, 0, $buf.Length)
  if ($n -le 0) { throw "no response" }
  $out = New-Object byte[] $n
  [Array]::Copy($buf, $out, $n)
  return ,$out
}

$label = "$Mode`://$RelayHost`:$Port"
try {
  $conn = Get-Stream
  $stream = $conn.s

  # --- step 1: unauthenticated Allocate -> expect 401 with REALM + NONCE
  $b1 = New-Object System.Collections.Generic.List[byte]
  Add-Attr $b1 0x0019 ([byte[]]@(17,0,0,0))
  $r1 = Send-Recv $stream (New-StunMsg 0x0003 $b1.ToArray())
  $a1 = Parse-Attrs $r1
  $code1 = ($r1[0] -shl 8) -bor $r1[1]
  if (-not $a1.ContainsKey(0x0014)) {
    Write-Output "$label : NO-REALM resp=0x$($code1.ToString('x4'))"
    $conn.c.Close(); exit
  }
  $realm = [Text.Encoding]::UTF8.GetString($a1[0x0014])
  $nonce = $a1[0x0015]

  # effective credentials
  $u = $User
  $p = $Pass
  if ($Secret -ne "") {
    $exp = [int][double]::Parse((Get-Date -UFormat %s)) + 86400
    $u = "$exp"
    $h = New-Object System.Security.Cryptography.HMACSHA1
    $h.Key = [Text.Encoding]::UTF8.GetBytes($Secret)
    $p = [Convert]::ToBase64String($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($u)))
  }

  # --- step 2: authenticated Allocate
  $b2 = New-Object System.Collections.Generic.List[byte]
  Add-Attr $b2 0x0019 ([byte[]]@(17,0,0,0))
  Add-Attr $b2 0x0006 ([Text.Encoding]::UTF8.GetBytes($u))
  Add-Attr $b2 0x0014 $a1[0x0014]
  Add-Attr $b2 0x0015 $nonce

  $msg = New-StunMsg 0x0003 $b2.ToArray()
  # rewrite length to include the 24-byte MESSAGE-INTEGRITY attribute
  $len = $b2.Count + 24
  $msg[2] = [byte](($len -shr 8) -band 0xff); $msg[3] = [byte]($len -band 0xff)
  $md5 = [System.Security.Cryptography.MD5]::Create()
  $key = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes("$u`:$realm`:$p"))
  $hm = New-Object System.Security.Cryptography.HMACSHA1
  $hm.Key = $key
  $integrity = $hm.ComputeHash($msg)
  $full = New-Object System.Collections.Generic.List[byte]
  $full.AddRange($msg)
  $full.Add(0); $full.Add(8); $full.Add(0); $full.Add(20)
  $full.AddRange($integrity)

  $r2 = Send-Recv $stream $full.ToArray()
  $type2 = ($r2[0] -shl 8) -bor $r2[1]
  $a2 = Parse-Attrs $r2
  if ($type2 -eq 0x0103) {
    $relay = "?"
    if ($a2.ContainsKey(0x0016)) {
      $v = $a2[0x0016]
      $pt = ((($v[2] -bxor 0x21) -shl 8) -bor ($v[3] -bxor 0x12))
      $ip = "$($v[4] -bxor 0x21).$($v[5] -bxor 0x12).$($v[6] -bxor 0xA4).$($v[7] -bxor 0x42)"
      $relay = "$ip`:$pt"
    }
    Write-Output "$label : ALLOCATE OK  relay=$relay  realm=$realm  tls=$($conn.proto)"
  } else {
    $err = "?"
    if ($a2.ContainsKey(0x0009)) {
      $v = $a2[0x0009]
      $err = "$($v[2] * 100 + $v[3]) $([Text.Encoding]::UTF8.GetString($v, 4, $v.Length - 4))"
    }
    Write-Output "$label : FAILED type=0x$($type2.ToString('x4')) err=[$err] realm=$realm"
  }
  $conn.c.Close()
} catch {
  Write-Output "$label : ERROR $($_.Exception.Message)"
}
