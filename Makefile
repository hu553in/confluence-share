PORT ?= 8098

.PHONY: build format preview

build:
	mvn clean verify

format:
	mvn spotless:apply

preview:
	bash scripts/preview.sh "$(PORT)"
