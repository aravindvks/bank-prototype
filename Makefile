COMPOSE = docker compose -f infra/compose/docker-compose.yml

.PHONY: build test infra up down logs smoke clean

build:            ## Compile and package every service
	./gradlew bootJar

test:             ## Unit tests, including the Temporal payment workflow test
	./gradlew test

infra:            ## PostgreSQL, Kafka (+ topics), Redis, Temporal and Temporal UI only
	$(COMPOSE) up -d

up: build         ## Infrastructure plus all services and the vendor simulator
	$(COMPOSE) --profile apps up -d --build

down:             ## Stop everything (keeps the database volume)
	$(COMPOSE) --profile apps down

logs:             ## Follow service logs
	$(COMPOSE) --profile apps logs -f --tail=100

smoke:            ## End-to-end check: customers, links, a same-bank payment and a card tap
	./scripts/smoke.sh

clean:            ## Stop everything and delete the database volume
	$(COMPOSE) --profile apps down -v
	./gradlew clean
