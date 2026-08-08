# Agent Observatory — benchmark repository.
#
#   make test              the fixture's own tests on the clean baseline
#   make verify-evaluator  the M1 exit criterion (known-good passes, known-bad fails)

SHELL     := /usr/bin/env bash
BENCH     ?= BE-001-customer-validation
BENCH_DIR := tasks/$(BENCH)
SERVICE   := sample-service

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@echo "Agent Observatory — benchmarks"
	@echo ""
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

.PHONY: test
test: ## Run the fixture's own test suite
	@cd $(SERVICE) && ./mvnw -B test

.PHONY: build
build: ## Package the fixture without running tests
	@cd $(SERVICE) && ./mvnw -B -q -DskipTests package

.PHONY: verify-evaluator
verify-evaluator: ## Prove the evaluator discriminates good from bad submissions
	@$(BENCH_DIR)/verify-evaluator.sh

.PHONY: evaluate
evaluate: ## Judge the current working tree against the baseline commit
	@$(BENCH_DIR)/evaluator.sh

.PHONY: baseline
baseline: ## Print the commit an evaluation would be measured against
	@git rev-parse HEAD

.PHONY: clean
clean: ## Remove build output and evaluation artifacts
	@rm -rf $(SERVICE)/target evaluation*.json
