.DEFAULT_GOAL := help

.PHONY: help build install release deploy-bridge verify bridge-test android-test hooks
.NOTPARALLEL:

help:
	@printf '%s\n' \
		'make build          Build the debug APK' \
		'make install        Build and install the APK (pick a device when needed)' \
		'make release        Deploy the bridge, then build and install the APK' \
		'make deploy-bridge  Build and restart the bridge service' \
		'make verify         Run the complete verification script' \
		'make bridge-test    Run bridge typecheck and tests' \
		'make android-test   Run Android JVM unit tests' \
		'make hooks          Point git at the repo'"'"'s .githooks directory'

build:
	@cd android && ./gradlew assembleDebug

install:
	@./scripts/install-app.sh

release:
	@./scripts/release.sh

deploy-bridge:
	@./scripts/deploy-bridge.sh

verify:
	@./scripts/verify.sh

bridge-test:
	@cd bridge && npm run typecheck && npm test

android-test:
	@cd android && ./gradlew testDebugUnitTest

hooks:
	@git config core.hooksPath .githooks
	@echo 'git hooks -> .githooks (post-commit auto-tags; SCOUTR_NO_AUTOTAG=1 to skip)'
