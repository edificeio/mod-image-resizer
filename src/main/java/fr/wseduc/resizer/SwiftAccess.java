package fr.wseduc.resizer;

import fr.wseduc.swift.SwiftClient;
import fr.wseduc.swift.storage.StorageObject;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;

import java.net.URI;

public class SwiftAccess implements FileAccess {

	private final SwiftClient client;

	public SwiftAccess(Vertx vertx, URI uri) {
		client = new SwiftClient(vertx, uri);
	}

	public void init(String username, String password, AsyncResultHandler<Void> handler) {
		client.authenticate(username, password, handler);
	}

	@Override
	public void read(String src, final Handler<ImageFile> handler) {
		String [] path = parsePath(src);
		if (path == null || path.length != 2) {
			handler.handle(null);
			return;
		}
		client.readFile(path[1], path[0], new AsyncResultHandler<StorageObject>() {
			@Override
			public void handle(AsyncResult<StorageObject> event) {
				if (event.succeeded()) {
					StorageObject f = event.result();
					handler.handle(new ImageFile(
							f.getBuffer().getBytes(),
							f.getFilename(),
							f.getContentType()
					));
				} else {
					handler.handle(null);
				}
			}
		});
	}

	@Override
	public void write(String dest, ImageFile img, final Handler<String> handler) {
		String [] path = parsePath(dest);
		if (path == null || path.length < 1) {
			handler.handle(null);
			return;
		}
		StorageObject o;
		if (path.length == 2 && path[1] != null && !path[1].trim().isEmpty()) {
			o = new StorageObject(path[1], new Buffer(img.getData()),
					img.getFilename(), img.getContentType());
		} else {
			o = new StorageObject(new Buffer(img.getData()),
					img.getFilename(), img.getContentType());
		}
		client.writeFile(o, path[0], new AsyncResultHandler<String>() {
			@Override
			public void handle(AsyncResult<String> event) {
				if (event.succeeded()) {
					handler.handle(event.result());
				} else {
					handler.handle(null);
				}
			}
		});
	}

	@Override
	public void close() {
		client.close();
	}

	private String[] parsePath(String path) {
		String[] p = path.split("://");
		if (p == null || p.length != 2) {
			return null;
		}
		return p[1].split(":");
	}

}
