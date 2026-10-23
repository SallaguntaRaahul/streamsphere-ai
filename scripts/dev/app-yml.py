#!/usr/bin/env python3
"""Writes a standard application.yml. Usage: app-yml.py <service> <local-port> [public-path ...] [--extra-file path]"""
import sys, pathlib
args = sys.argv[1:]
extra = ""
if "--extra-file" in args:
    i = args.index("--extra-file"); extra = pathlib.Path(args[i+1]).read_text(); del args[i:i+2]
svc, port, *public = args
root = pathlib.Path(__file__).resolve().parents[2]
pp = "[" + ", ".join(public) + "]"
yml = f"""spring:
  application.name: {svc}
  threads.virtual.enabled: true
  datasource:
    url: jdbc:postgresql://${{DB_HOST:localhost}}:${{DB_PORT:15432}}/{svc}
    username: {svc}_svc
    password: ${{DB_PASSWORD:{svc}_local_pw}}
    hikari.maximum-pool-size: 6
  flyway.locations: classpath:db/migration,classpath:db/common
  rabbitmq:
    publisher-confirm-type: simple
    username: ${{RABBITMQ_USER:streamsphere}}
    password: ${{RABBITMQ_PASSWORD:local-rabbit-pw}}
  data.redis:
    port: ${{REDIS_PORT:16379}}
    timeout: 300ms
server:
  port: ${{SERVER_PORT:{port}}}
  shutdown: graceful
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.probes.enabled: true
  tracing.sampling.probability: 1.0
  metrics.tags.service: ${{spring.application.name}}
streamsphere:
  service-name: {svc}
  security:
    issuer: ${{STREAMSPHERE_SECURITY_ISSUER:http://localhost:8180/realms/streamsphere}}
    jwk-set-uri: ${{STREAMSPHERE_SECURITY_JWK_SET_URI:http://localhost:8180/realms/streamsphere/protocol/openid-connect/certs}}
    public-paths: {pp}
  service-auth:
    token-uri: ${{STREAMSPHERE_SERVICE_AUTH_TOKEN_URI:http://localhost:8180/realms/streamsphere/protocol/openid-connect/token}}
    client-id: svc-{svc}
    client-secret: ${{SERVICE_CLIENT_SECRET:{svc}-local-secret}}
{extra}---
spring.config.activate.on-profile: docker
spring.datasource.url: jdbc:postgresql://postgres:5432/{svc}
spring.data.redis.port: 6379
logging.structured.format.console: ecs
"""
out = root / "services" / svc / "src/main/resources/application.yml"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(yml)
