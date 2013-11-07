package com.wse.io;

import org.imgscalr.Scalr;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import static org.imgscalr.Scalr.*;


public class ImageResizer extends BusModBase implements Handler<Message<JsonObject>> {

	private Map<String, FileAccess> fileAccessProviders = new HashMap<>();

	@Override
	public void start() {
		super.start();
		fileAccessProviders.put("file", new FileSystemFileAccess());
		JsonObject gridfs = config.getObject("gridfs");
		if (gridfs != null) {
			String host = gridfs.getString("host", "localhost");
			int port = gridfs.getInteger("port", 27017);
			String dbName = gridfs.getString("db_name");
			String username = gridfs.getString("username");
			String password = gridfs.getString("password");
			int poolSize = gridfs.getInteger("pool_size", 10);
			if (dbName != null) {
				try {
					fileAccessProviders.put("gridfs",
						new GridFsFileAccess(host, port, dbName, username, password, poolSize));
				} catch (UnknownHostException e) {
					logger.error("Invalid mongoDb configuration.", e);
				}
			}
		}
		eb.registerHandler(config.getString("address"), this);
		logger.info("BusModBase: Image resizer starts on address: " + config.getString("address"));
	}

	@Override
	public void stop() {
		super.stop();
		for (FileAccess fa: fileAccessProviders.values()) {
			fa.close();
		}
	}

	@Override
	public void handle(Message<JsonObject> m) {
		switch(m.body().getString("action", "")) {
			case "resize" :
				resize(m);
				break;
			default :
				sendError(m, "Invalid or missing action");
		}
	}

	private void resize(final Message<JsonObject> m) {
		final Integer width = m.body().getInteger("width");
		final Integer height = m.body().getInteger("height");
		if (width == null && height == null) {
			sendError(m, "Invalid size.");
			return;
		}
		final FileAccess fSrc = getFileAccess(m, m.body().getString("src"));
		if (fSrc == null) {
			return;
		}
		final FileAccess fDest = getFileAccess(m, m.body().getString("dest"));
		if (fDest == null) {
			return;
		}
		fSrc.read(m.body().getString("src"), new Handler<ImageFile>() {
			@Override
			public void handle(ImageFile src) {
				if (src == null) {
					sendError(m, "Input file not found.");
					return;
				}
				try {
					BufferedImage srcImg = ImageIO.read(src.getInputStream());
					BufferedImage resized;
					if (width != null && height != null) {
						resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
								Mode.FIT_EXACT, width, height);
					} else if (height != null) {
						resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
								Mode.FIT_TO_HEIGHT, height);
					} else {
						resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY, width);
					}
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					ImageIO.write(resized, getExtension(src.getFilename()), out);
					ImageFile outImg = new ImageFile(out.toByteArray(), src.getFilename(),
							src.getContentType());
					fDest.write(m.body().getString("dest"), outImg, new Handler<Boolean>() {
						@Override
						public void handle(Boolean result) {
							if (Boolean.TRUE.equals(result)) {
								sendOK(m);
							} else {
								sendError(m, "Error writing file.");
							}
						}
					});
				} catch (IOException e) {
					logger.error("Error processing image.", e);
					sendError(m, "Error processing image.", e);
				}
			}
		});
	}

	private FileAccess getFileAccess(Message<JsonObject> m, String path) {
		if (path == null || !path.contains("://")) {
			sendError(m, "Invalid path : " + path);
			return null;
		}
		String protocol = path.substring(0, path.indexOf("://"));
		FileAccess fa = fileAccessProviders.get(protocol);
		if (fa == null) {
			sendError(m, "Invalid file protocol : " + protocol);
			return null;
		}
		return fa;
	}

	private String getExtension(String fileName) {
		if (fileName != null) {
			int idx = fileName.lastIndexOf('.');
			if (idx > 0 && fileName.length() > idx + 1) {
				return fileName.substring(idx + 1);
			}
		}
		return "";
	}

}
