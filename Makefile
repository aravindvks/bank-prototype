# Gradle 8.10 cannot run on Java 22+ (e.g. "Unsupported class file major version 69" on Java 25).
# Use a Java 21 install when one is found, whatever the default java is.
JDK21 := $(shell /usr/libexec/java_home -v 21 -F 2>/dev/null)
ifeq ($(JDK21),)
JDK21 := $(firstword $(wildcard /usr/lib/jvm/java-21-openjdk-* /usr/lib/jvm/temurin-21-jdk-*))
endif
ifneq ($(JDK21),)
export JAVA_HOME := $(JDK21)
endif

COMPOSE = docker compose -f infra/compose/docker-compose.yml

.PHONY: build test infra up down logs smoke clean dashboards

build:            ## Compile and package every service
	./gradlew bootJar

test:             ## Unit tests, including the Temporal payment workflow test
	./gradlew test

infra:            ## PostgreSQL, Kafka (+ topics), Redis, Temporal and Temporal UI only
	$(COMPOSE) up -d

up: build         ## Infrastructure plus all services and the vendor simulator
	$(COMPOSE) --profile apps up -d --build
	@echo "Web http://localhost:3000 · Grafana http://localhost:3001 · Temporal http://localhost:8233"

down:             ## Stop everything (keeps the database volume)
	$(COMPOSE) --profile apps down

logs:             ## Follow service logs
	$(COMPOSE) --profile apps logs -f --tail=100

smoke:            ## End-to-end check of every flow, including traces, metrics and logs
	./scripts/smoke.sh

dashboards:       ## Regenerate the Grafana dashboards from infra/observability/grafana/generate_dashboards.py
	python3 infra/observability/grafana/generate_dashboards.py

clean:            ## Stop everything and delete the database volume
	$(COMPOSE) --profile apps down -v
	./gradlew clean
