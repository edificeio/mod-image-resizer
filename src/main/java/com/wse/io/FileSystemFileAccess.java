package com.wse.io;

import org.vertx.java.core.Handler;

public class FileSystemFileAccess implements FileAccess{

	@Override
	public void read(String src, Handler<ImageFile> handler) {
	}

	@Override
	public void write(String dest, ImageFile img, Handler<String> handler) {
	}

	@Override
	public void close() {
	}

}
