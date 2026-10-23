# StreamSphere AI — developer entrypoints. Requires JDK 21, Maven 3.9, Docker.
SHELL := /bin/bash
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)
export JAVA_HOME
MVN := mvn -B -q
SERVICES := gateway profile catalog creator upload media playback history search moderation analytics notifications

.PHONY: help build test test-fast images up up-full down logs ps e2e seed eval clean web web-test k8s-up k8s-down helm-lint

help:            ## list targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/\t/'

build:           ## compile + package all JVM modules on the host
	$(MVN) install -DskipTests

test:            ## unit + Testcontainers integration tests
	$(MVN) verify

test-fast:       ## unit tests only
	$(MVN) -Pfast verify

images: build    ## build all images from host-built jars (fast local loop)
	docker compose build

up: images       ## start local-core
	docker compose up -d
	./scripts/wait-healthy.sh

up-full: images  ## start local-full (Kafka, observability, tusd, mailpit)
	docker compose --profile full up -d
	./scripts/wait-healthy.sh

down:            ## stop (keeps volumes)
	docker compose --profile full down

logs:
	docker compose logs -f --tail=100

ps:
	docker compose ps

e2e:             ## end-to-end API tests against the running stack
	python3 tests/e2e/run_media_pipeline.py
	python3 tests/e2e/run_platform_e2e.py

seed:            ## publish the CC0 demo catalog through the real pipeline
	python3 scripts/seed_demo.py

eval:            ## offline recommendation evaluation (synthetic interactions)
	docker compose exec -T recommendations python -m eval.evaluate

web:             ## Angular dev server on :4200
	cd apps/web-angular && npm start

web-test:
	cd apps/web-angular && npm test && npx playwright test

clean:
	$(MVN) clean

drills:          ## failure-injection drills against the running stack
	python3 tests/resilience/drills.py

load:            ## k6 API load test (local only)
	docker run --rm -i --add-host host.docker.internal:host-gateway -v "$(PWD)/tests/load:/scripts:ro" grafana/k6:1.8.2 run /scripts/api-load.js

backup:
	./scripts/backup.sh

browser-test:    ## Playwright + axe in real Chrome (needs web dev server on :4200)
	cd tests/e2e-web && PLAYWRIGHT_BROWSERS_PATH=$(PWD)/.playwright npx playwright test
