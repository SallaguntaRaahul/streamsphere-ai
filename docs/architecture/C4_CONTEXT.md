# C4 — System context

```mermaid
C4Context
  title StreamSphere AI — system context
  Person(viewer, "Viewer", "Browses, searches, streams, saves titles")
  Person(creator, "Creator", "Uploads authorized films, captions, publishes, reads analytics")
  Person(mod, "Moderator / Admin", "Reviews content, takedowns, platform health & cost")
  System(ss, "StreamSphere AI", "Microservices VOD platform")
  System_Ext(idp, "Keycloak (OIDC)", "Identity, PKCE login, service accounts")
  System_Ext(obj, "Object storage", "MinIO (local) / Cloudflare R2 (cloud, not provisioned)")
  System_Ext(cdn, "Static hosting", "Cloudflare Pages (planned, not deployed)")
  Rel(viewer, ss, "HTTPS + HLS")
  Rel(creator, ss, "HTTPS; direct multipart PUT to storage")
  Rel(mod, ss, "HTTPS")
  Rel(ss, idp, "JWKS / client credentials")
  Rel(ss, obj, "S3 API, presigned URLs")
  Rel(viewer, obj, "Segments via 60-s presigned GET")
```
Media bytes never flow through the gateway or any service: browsers PUT parts to storage and GET segments
from storage using short-lived signatures issued after authorization.
