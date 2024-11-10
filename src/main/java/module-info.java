open module unreal.archive.submit {
	requires java.base;
	requires java.net.http;
	requires java.desktop;

	requires unreal.archive.common;
	requires unreal.archive.content;
	requires unreal.archive.storage;
	requires unreal.archive.indexing;

	requires org.slf4j;
	requires org.slf4j.simple;

	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.databind;

	requires org.eclipse.jgit;
	requires org.kohsuke.github.api;

	requires xnio.api;
	requires undertow.core;
}
