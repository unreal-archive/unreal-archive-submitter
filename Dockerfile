FROM debian:buster-slim

RUN echo "deb http://deb.debian.org/debian stable non-free" >> /etc/apt/sources.list \
    && mkdir -p /usr/share/man/man1 \
    && apt-get update \
    && apt-get install -y clamav p7zip-full unrar unzip openjdk-11-jre-headless --no-install-recommends \
    && rm -rf /var/lib/apt/lists/* \
    && freshclam

ADD dist/unreal-archive-submitter-dist.tgz /unreal-archive/
ADD resources/docker-entrypoint.sh /unreal-archive/

ENTRYPOINT ["/unreal-archive/docker-entrypoint.sh"]
