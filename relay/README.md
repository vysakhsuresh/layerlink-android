# The relay, and why cross-country sharing was failing

## What was wrong

A share between two peers on unrelated networks — a phone on mobile data in the UAE, a viewer
in India — can only work over a **TURN relay**. STUN just tells each side its own public
address; it cannot open a path through carrier NAT, and both ends of that pairing are behind
carrier NAT. Without a relay, ICE never finds a working candidate pair, and because ICE does not
fail loudly, both ends sit on "waiting" forever with no error at all.

A relay *was* configured, in three places with identical values — this app and both web pages:

```
turn:openrelay.metered.ca:80           username: openrelayproject
turn:openrelay.metered.ca:443          credential: openrelayproject
turn:openrelay.metered.ca:443?transport=tcp
```

It no longer works. Two independent problems:

1. **The credentials are rejected.** `openrelay.metered.ca` now answers the authenticated TURN
   Allocate with `400 Bad Request` for *every* username — including deliberately wrong ones —
   while a working coturn (`relay1.expressturn.com`) correctly answers a bad password with
   `401 Unauthorized`. Metered moved the Open Relay Project to per-account API keys; the old
   public static credential pair is no longer a valid user. No relay candidate was ever
   allocated, so the "TURN fallback" was inert.

2. **Even with valid credentials, the fallback transport was wrong.** The last entry is *plain*
   TURN on port 443. On that server, 443 answers only TLS: a plain-TCP Allocate there draws no
   reply whatsoever. There was no `turns:` (TURN-over-TLS) entry at all — and `turns:` on 443 is
   precisely the transport that survives a restrictive ISP, because it is a real TLS handshake
   on the HTTPS port and indistinguishable from web traffic.

Neither failure was visible anywhere: the app's only symptom was "waiting for viewer", forever.

## What changed in the app

- The relay list is **resolved at runtime**, not hardcoded — `IceConfig` / `IceConfigStore`.
  Free relay credentials rot, and re-hardcoding a different free relay would only reset the
  clock on the same outage, with a Play review in the way of the fix.
- A relay hostname is expanded into **every transport worth trying**, `turns:` included:
  `udp:3478`, `tcp:3478`, `tcp:80`, `tcp:443`, `turns:443`, `turns:5349`.
- The app **says whether it actually got a relay**, on the share screen while the link is still
  unsent, and names it as the cause in the failure screen when it didn't.
- **Relay server → Test** runs the real WebRTC ICE agent in relay-only mode against the
  credentials as typed, so a wrong password is caught in seconds instead of by a viewer in
  another country who simply never connects.
- ICE itself is configured for hostile networks: TCP candidates enabled explicitly, continual
  gathering (so a Wi-Fi → mobile-data switch mid-broadcast re-offers candidates instead of
  killing the session), and `presumeWritableWhenFullyRelayed` to drop a round trip from the
  slowest paths. The reconnect grace window went from 20s to 35s, because a relayed TLS path
  across a continent can legitimately take longer than 20s to settle.

## What you need to do

### 1. Get a relay (about a minute, free)

Any TURN server works. Free tiers that give you a host, a username and a password:

| Provider | Free tier | Notes |
|---|---|---|
| [expressturn.com](https://www.expressturn.com) | 500 GB/month | Static credentials, instant. Simplest fit for this app. |
| [metered.ca](https://www.metered.ca/tools/openrelay/) | 20 GB/month | Needs an API key now; use the dashboard's static credentials. |
| [Cloudflare Calls](https://developers.cloudflare.com/calls/turn/) | 1 TB/month | Needs an API token to mint credentials. |

Screen sharing at Data Saver quality runs roughly 0.4 Mbit/s ≈ 180 MB/hour of relayed traffic,
so 500 GB is a lot of sharing. Relayed traffic is still end-to-end encrypted (DTLS-SRTP); the
relay forwards packets it cannot read.

### 2. Put it in the app

**Relay server → Set up**, paste host/username/password, press **Test**. A pass means a
broadcast started right then would have a relayed path. Then **Save**.

That is enough to fix the phone in your hand, with no new build.

### 3. Publish `layerlink-ice.json` so every client gets it

Edit `layerlink-ice.json` in this directory with your relay and upload it to
`https://layerbit.co.in/tools/layerlink-ice.json`. The app already fetches it (cached 6 hours,
falling back to the last good copy, then to STUN-only), so from then on the relay can be
rotated for every installed copy by editing one static file — no app release.

A device relay set in step 2 takes priority over this file, so clear it if you want the phone to
follow the hosted config.

### 4. Patch the two web pages

Both pages still carry the dead hardcoded list, and the viewer is the other half of every
session. In the `layerbit-site` repo, `layerlink-sharer.body.html` (~line 626) and
`layerlink-viewer.body.html` (~line 546) each have an `iceServers` array. Replace the
`openrelay.metered.ca` entries with the same relay, as the full transport matrix:

```js
async function loadIceServers() {
  const fallback = [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
  ];
  try {
    const res = await fetch('/tools/layerlink-ice.json', { cache: 'no-cache' });
    if (!res.ok) return fallback;
    const cfg = await res.json();
    const servers = (cfg.stun || []).map(urls => ({ urls }));
    for (const t of cfg.turn || []) {
      const urls = t.urls || [
        `turn:${t.host}:3478?transport=udp`,
        `turn:${t.host}:3478?transport=tcp`,
        `turn:${t.host}:80?transport=tcp`,
        `turn:${t.host}:443?transport=tcp`,
        `turns:${t.host}:443?transport=tcp`,
        `turns:${t.host}:5349?transport=tcp`
      ];
      servers.push({ urls, username: t.username, credential: t.credential });
    }
    return servers.length ? servers : fallback;
  } catch {
    return fallback;
  }
}

const configuration = { iceServers: await loadIceServers() };
const peer = new RTCPeerConnection(configuration);
```

Note `turns:` in that list — its absence is half of why the old fallback could never have
worked.

## Checking a relay yourself

`relay-check.ps1` in this directory performs a real TURN Allocate (long-term credentials, over
TCP and over TLS) and prints the allocated relay address, or the server's actual error code. It
is what established that `openrelay.metered.ca` rejects every username with `400` while a
working relay answers a bad password with `401`:

```powershell
./relay-check.ps1 -RelayHost relay1.expressturn.com -Port 3478 -User u -Pass p -Mode tcp
./relay-check.ps1 -RelayHost relay1.expressturn.com -Port 5349 -User u -Pass p -Mode tls
```
