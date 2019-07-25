FROM debian:buster-slim

RUN echo "deb http://deb.debian.org/debian stable non-free" >> /etc/apt/sources.list \
    && mkdir -p /usr/share/man/man1 \
    && apt-get update \
    && apt-get install -y clamav clamdscan clamav-daemon p7zip-full unrar unzip openjdk-11-jre-headless --no-install-recommends \
    && rm -rf /var/lib/apt/lists/* \
    && apt-get autoclean \
    && freshclam

ADD build/libs/unreal-archive-submitter /unreal-archive-submitter

ENTRYPOINT ["/unreal-archive-submitter"]
