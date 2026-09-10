# HTTP attempt diagnostics

The named Catalog and Identity groups retain their configured Apache HC5
transport, timeouts and disabled automatic retries. `OrderHttpDiagnostics`
decorates its request factory below token caching. It sets the current trusted
correlation at execution, delegates the existing streaming body callback without
buffering, and records one `http.client` INFO when response processing closes or
an observed transport failure ends the attempt. Body reads are only forwarded;
the observer never reads ahead, copies payloads or parses response content.
A body-read timeout after 200 headers remains a failed interaction with the
observed 200 status. Known Content-Length values are advertised lengths, not a
claim that the peer received every byte. Unknown lengths and status are omitted.

Logs capture the initiating operation's Context and trusted diagnostic identity,
then open that snapshot only for the final synchronous logging callback. They
retain current C independently of the token-cache key and leave Agent W3C
injection and HTTP Span ownership intact. INFO cause projection supplies only
the exception type through the shared formatter; error text and stack traces
remain absent. The client adds no retry, recovery WARN or final ERROR owner.

`OrderHttpDiagnosticsIT` uses these actual named groups with real HTTP peers.
It checks cache reuse across different C values and sampled/unsampled operation
contexts, base-path preservation, rejection without a fabricated Catalog call,
no-response failure and body-read timeout. The locked-Agent outbound fixture in
`verification/governed-observability` additionally reconciles received W3C
headers with exported client/server Spans. Test-provided operation Context comes
from the Agent's standard propagator; full ingress-to-service assembly remains
the separate cross-service acceptance boundary.
