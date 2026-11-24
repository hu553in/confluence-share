.DEFAULT_GOAL := check

# macOS ships Make 3.81, which ignores .SHELLFLAGS and .ONESHELL.
SHELL := /bin/bash -euo pipefail

PRETTIER := bun --silent prettier -u
STYLELINT := bun --silent stylelint --max-warnings 0
ACTIONLINT := bun --silent github-actionlint
SHELLCHECK := bunx --minimum-release-age 259200 shellcheck
SHFMT := shfmt
SHELL_FILES := $(wildcard scripts/*.sh)
PREVIEW_CLASSPATH := dependency:build-classpath -Dmdep.outputFile=target/preview-classpath.txt -DincludeScope=test
PORT ?= 8098

.PHONY: install-deps
install-deps:
	bun install --frozen-lockfile

.PHONY: lint
lint: lint-files
	./mvnw validate

.PHONY: lint-files
lint-files:
	$(PRETTIER) -c .
	$(STYLELINT) 'src/main/resources/**/*.css'
	bun fmt --no-config --check bunfig.toml mise.toml
	$(SHFMT) -d $(SHELL_FILES)
	$(SHELLCHECK) $(SHELL_FILES)

.PHONY: lint-fix
lint-fix:
	$(STYLELINT) --fix 'src/main/resources/**/*.css'
	$(PRETTIER) -w .
	bun fmt --no-config bunfig.toml mise.toml
	$(SHFMT) -w $(SHELL_FILES)
	./mvnw spotless:apply

.PHONY: check-hooks
check-hooks:
	bunx --minimum-release-age 259200 lefthook validate

.PHONY: check-workflows
check-workflows:
	$(ACTIONLINT)

.PHONY: check-preview
check-preview: prepare-preview
	bash scripts/check-preview.sh

.PHONY: prepare-preview
prepare-preview:
	./mvnw -q test-compile $(PREVIEW_CLASSPATH)

.PHONY: check
check: lint-files check-hooks check-workflows
	./mvnw verify $(PREVIEW_CLASSPATH)
	bash scripts/check-preview.sh

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
preview: prepare-preview
	bash scripts/preview.sh "$(PORT)"
