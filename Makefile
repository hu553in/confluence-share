.DEFAULT_GOAL := check

# macOS ships Make 3.81, which ignores .SHELLFLAGS and .ONESHELL.
SHELL := /bin/bash -euo pipefail

PRETTIER := bun --silent prettier -u
STYLELINT := bun --silent stylelint --max-warnings 0
ACTIONLINT := bun --silent github-actionlint
TAPLO := bun --silent taplo
SHELLCHECK := bunx shellcheck
SHFMT := shfmt
PREK ?= prek
SHELL_FILES := $(wildcard scripts/*.sh)
PORT ?= 8098

.PHONY: install-deps
install-deps:
	bun install --frozen-lockfile

.PHONY: lint
lint:
	$(PRETTIER) -c .
	$(STYLELINT) 'src/main/resources/**/*.css'
	$(TAPLO) fmt --check
	$(SHFMT) -d $(SHELL_FILES)
	$(SHELLCHECK) $(SHELL_FILES)
	./mvnw validate

.PHONY: lint-fix
lint-fix:
	$(PRETTIER) -w .
	$(STYLELINT) --fix 'src/main/resources/**/*.css'
	$(TAPLO) fmt
	$(SHFMT) -w $(SHELL_FILES)
	./mvnw spotless:apply

.PHONY: check-hooks
check-hooks:
	$(PREK) validate-config prek.toml

.PHONY: check-workflows
check-workflows:
	$(ACTIONLINT)

.PHONY: check-renovate
check-renovate:
	bunx --package renovate renovate-config-validator --strict --no-global renovate.json

.PHONY: check-preview
check-preview:
	bash scripts/check-preview.sh

.PHONY: check
check: lint check-hooks check-workflows check-renovate
	./mvnw verify
	$(MAKE) check-preview

.PHONY: check-fix
check-fix: lint-fix
	$(MAKE) check

.PHONY: test
test:
	./mvnw test

.PHONY: build
build:
	./mvnw package -DskipTests

.PHONY: preview
preview:
	bash scripts/preview.sh "$(PORT)"
