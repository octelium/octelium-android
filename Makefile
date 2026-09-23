.PHONY: release release-major release-minor release-patch sync-proto build-liboctelium build-host-libs \
	test lint build check-alignment

release:
	@./scripts/release.sh "$(VERSION)"

release-major:
	@./scripts/release.sh major

release-minor:
	@./scripts/release.sh minor

release-patch:
	@./scripts/release.sh patch

sync-proto:
	./scripts/sync-proto.sh

build-liboctelium:
	./scripts/build-liboctelium.sh

build-host-libs:
	./scripts/build-host-libs.sh

test:
	./gradlew :core:test :app:testDebugUnitTest

lint:
	./gradlew :app:lintDebug

build:
	./gradlew :app:assembleDebug

check-alignment:
	./scripts/check-elf-alignment.sh app/build/outputs/apk/debug/app-debug.apk
