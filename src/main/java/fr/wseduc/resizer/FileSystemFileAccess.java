/*
 * Copyright © WebServices pour l'Éducation, 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.wseduc.resizer;

import fr.wseduc.swift.storage.DefaultAsyncResult;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

public class FileSystemFileAccess implements FileAccess {

	private static final Logger log = LoggerFactory.getLogger(FileSystemFileAccess.class);
	private final FileSystem fs;
	private final boolean flat;

	public FileSystemFileAccess(Vertx vertx, boolean flat) {
		this.fs = vertx.fileSystem();
		this.flat = flat;
	}

	@Override
	public void read(String src, final Handler<ImageFile> handler) {
		final String[] path = parsePath(src);
		if (path == null || path.length != 2 || !path[0].startsWith("/")) {
			handler.handle(null);
			return;
		}
		final String p;
		try {
			p = getFilePath(path[0], path[1]);
		} catch (FileNotFoundException e) {
			log.error(e.getMessage(), e);
			handler.handle(null);
			return;
		}
		fs.readFile(p, new Handler<AsyncResult<Buffer>>() {
			@Override
			public void handle(AsyncResult<Buffer> ar) {
				if (ar.succeeded()) {
					handler.handle(new ImageFile(ar.result().getBytes(), getFileName(p), getContentType(p)));
				} else {
					handler.handle(null);
				}
			}
		});
	}

	@Override
	public void write(String dest, final ImageFile img, final Handler<String> handler) {
		final String[] path = parsePath(dest);
		if (path == null || path.length < 1 || !path[0].startsWith("/")) {
			handler.handle(null);
			return;
		}

		final String id;
		if (path.length == 2 && path[1] != null && !path[1].trim().isEmpty()) {
			id = path[1];
		} else {
			id = UUID.randomUUID().toString();
		}
		final String p;
		try {
			p = getFilePath(path[0], id);
		} catch (FileNotFoundException e) {
			log.error(e.getMessage(), e);
			handler.handle(null);
			return;
		}
		mkdirsIfNotExists(id, p, new Handler<AsyncResult<Void>>() {
			@Override
			public void handle(AsyncResult<Void> event) {
				if (event.succeeded()) {
					fs.writeFile(p, Buffer.buffer(img.getData()), new Handler<AsyncResult<Void>>() {
						@Override
						public void handle(AsyncResult<Void> ar) {
							if (ar.succeeded()) {
								handler.handle(id);
							} else {
								handler.handle(null);
							}
						}
					});
				} else {
					log.error(event.cause().getMessage(), event.cause());
					handler.handle(null);
				}
			}
		});
	}

	@Override
	public void close() {
	}

	private String[] parsePath(String path) {
		String[] p = path.split("://");
		if (p == null || p.length != 2) {
			return null;
		}
		return p[1].split(":");
	}


	private String getFilePath(String basePath, String file) throws FileNotFoundException {
		if (file != null && !file.isEmpty()) {
			if (flat) {
				return basePath + file;
			}
			final int startIdx = file.lastIndexOf(File.separatorChar) + 1;
			final int extIdx = file.lastIndexOf('.');
			String filename = (extIdx > 0) ? file.substring(startIdx, extIdx) : file.substring(startIdx);
			if (!filename.isEmpty()) {
				final int l = filename.length();
				if (l < 4) {
					filename = "0000".substring(0, 4 - l) + filename;
				}
				return basePath + filename.substring(l - 2) + File.separator + filename.substring(l - 4, l - 2) +
						File.separator + filename;
			}
		}
		throw new FileNotFoundException("Invalid file : " + file);
	}

	private void mkdirsIfNotExists(String id, String path, final Handler<AsyncResult<Void>> h) {
		final String dir = path.substring(0, path.length() - id.length());
		fs.exists(dir, new Handler<AsyncResult<Boolean>>() {
			@Override
			public void handle(AsyncResult<Boolean> event) {
				if (event.succeeded()) {
					if (Boolean.FALSE.equals(event.result())) {
						fs.mkdirs(dir, new Handler<AsyncResult<Void>>() {
							@Override
							public void handle(AsyncResult<Void> event) {
								h.handle(event);
							}
						});
					} else {
						h.handle(new DefaultAsyncResult<>((Void) null));
					}
				} else {
					h.handle(new DefaultAsyncResult<Void>(event.cause()));
				}
			}
		});
	}

	private String getFileName(String path) {
		if (path != null) {
			int idx = path.lastIndexOf('/');
			if (idx > 0 && path.length() > idx + 1) {
				return path.substring(idx + 1);
			}
		}
		return "";
	}

	private String getContentType(String p) {
		try {
			Path source = Paths.get(p);
			return Files.probeContentType(source);
		} catch (IOException e) {
			log.error("Error finding mime type.", e);
			return "";
		}
	}

}
