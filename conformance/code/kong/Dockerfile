FROM kong:latest

USER root
# Build context is the repo root (.github/workflows/build.yaml sets
# `context: .` with `file: src/services/kong/Dockerfile`), so the COPY
# source must be the full repo-relative path of the plugins directory.
COPY ./src/services/kong/plugins/ /usr/local/share/lua/5.1/kong/plugins/
USER kong
