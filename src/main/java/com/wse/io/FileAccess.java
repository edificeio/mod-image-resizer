package com.wse.io;

import org.vertx.java.core.Handler;

public interface FileAccess {

	void read(String src, Handler<ImageFile> handler);

	void write(String dest, ImageFile img, Handler<Boolean> handler);

	void close();

}
