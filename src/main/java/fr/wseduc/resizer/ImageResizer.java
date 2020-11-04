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

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.mongodb.ReadPreference;
import org.imgscalr.Scalr;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.vertx.java.busmods.BusModBase;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static fr.wseduc.webutils.Utils.getOrElse;
import static org.imgscalr.Scalr.*;


public class ImageResizer extends BusModBase implements Handler<Message<JsonObject>> {

	public static final String JAI_TIFFIMAGE_WRITER = "com.sun.media.imageioimpl.plugins.tiff.TIFFImageWriter";
	private Map<String, FileAccess> fileAccessProviders = new HashMap<>();
	private boolean allowImageEnlargement = false;

	@Override
	public void start(final Future<Void> startedResult) {
		super.start();
		fileAccessProviders.put("file", new FileSystemFileAccess(vertx,
				config.getBoolean("fs-flat", false)));
		allowImageEnlargement = config.getBoolean("allow-image-enlargement", false);
		JsonObject gridfs = config.getJsonObject("gridfs");
		if (gridfs != null) {
			String host = gridfs.getString("host", "localhost");
			int port = gridfs.getInteger("port", 27017);
			String dbName = gridfs.getString("db_name");
			String username = gridfs.getString("username");
			String password = gridfs.getString("password");
			ReadPreference readPreference = ReadPreference.valueOf(
					gridfs.getString("read_preference", "primary"));
			int poolSize = gridfs.getInteger("pool_size", 10);
			boolean autoConnectRetry = gridfs.getBoolean("auto_connect_retry", true);
			int socketTimeout = gridfs.getInteger("socket_timeout", 60000);
			boolean useSSL = gridfs.getBoolean("use_ssl", false);
			JsonArray seedsProperty = gridfs.getJsonArray("seeds");
			if (dbName != null) {
				try {
					fileAccessProviders.put("gridfs",
						new GridFsFileAccess(host, port, dbName, username, password, poolSize,
								readPreference, autoConnectRetry, socketTimeout, useSSL, seedsProperty));
				} catch (UnknownHostException e) {
					logger.error("Invalid mongoDb configuration.", e);
				}
			}
		}
		JsonObject swift = config.getJsonObject("swift");
		if (swift != null) {
			String uri = swift.getString("uri");
			String username = swift.getString("user");
			String password = swift.getString("key");
			if (uri != null && username != null && password != null) {
				try {
					final SwiftAccess swiftAccess = new SwiftAccess(vertx, new URI(uri));
					swiftAccess.init(username, password, new Handler<AsyncResult<Void>>() {
						@Override
						public void handle(AsyncResult<Void> event) {
							if (event.succeeded()) {
								fileAccessProviders.put("swift", swiftAccess);
							} else {
								logger.error("Swift authentication error", event.cause());
							}
							registerHandler(startedResult);
						}
					});
				} catch (URISyntaxException e) {
					logger.error("Invalid swift uri.", e);
				}
			}
		} else {
			registerHandler(startedResult);
		}
	}

	private void registerHandler(Future<Void> startedResult) {
		eb.consumer(config.getString("address", "image.resizer"), this);
		logger.info("BusModBase: Image resizer starts on address: " + config.getString("address"));
		startedResult.complete();
	}

	@Override
	public void stop() throws Exception {
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
			case "crop" :
				crop(m);
				break;
			case "resizeMultiple" :
				resizeMultiple(m);
				break;
			case "compress" :
				compress(m);
				break;
			default :
				sendError(m, "Invalid or missing action");
		}
	}

	private void compress(final Message<JsonObject> m) {
		final Number quality = m.body().getFloat("quality");
		if (quality == null || quality.floatValue() > 1f || quality.floatValue() <= 0f) {
			sendError(m, "Invalid quality.");
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
					persistImage(src, srcImg, srcImg, fDest, quality.floatValue(), m);
				} catch (IOException e) {
					logger.error("Error processing image.", e);
					sendError(m, "Error processing image.", e);
				}
			}
		});
	}

	private void crop(final Message<JsonObject> m) {
		final Integer width = m.body().getInteger("width");
		final Integer height = m.body().getInteger("height");
		final Integer x = getOrElse(m.body().getInteger("x"), 0);
		final Integer y = getOrElse(m.body().getInteger("y"), 0);
		final float quality = getOrElse(m.body().getFloat("quality"), 0.8f);
		if (width == null || height == null) {
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
					if (srcImg.getWidth() < (x + width) || srcImg.getHeight() < (y + height)) {
						sendError(m, "Source image too small for crop.");
						return;
					}
					BufferedImage cropped = Scalr.crop(srcImg, x, y, width, height);
					persistImage(src, srcImg, cropped, fDest, quality, m);
				} catch (IOException e) {
					logger.error("Error processing image.", e);
					sendError(m, "Error processing image.", e);
				}
			}
		});
	}

	private void resize(final Message<JsonObject> m) {
		final Integer width = m.body().getInteger("width");
		final Integer height = m.body().getInteger("height");
		final boolean stretch = getOrElse(m.body().getBoolean("stretch"), false);
		final float quality = getOrElse(m.body().getFloat("quality"), 0.8f);
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
					if(srcImg != null)
					{
						BufferedImage resized = doResize(width, height, stretch, srcImg);
						persistImage(src, srcImg, resized, fDest, quality, m);
					}
					else
					{
						logger.error("Unsupported image type for: " + m.body().getString("src"));
						sendError(m, "Unsupported image type");
					}
				} catch (IOException e) {
					logger.error("Error processing image.", e);
					sendError(m, "Error processing image.", e);
				}
			}
		});
	}

	private void resizeMultiple(final Message<JsonObject> m) {
		final JsonArray destinations = m.body().getJsonArray("destinations");
		final float quality = getOrElse(m.body().getFloat("quality"), 0.8f);
		if (destinations == null || destinations.size() == 0) {
			sendError(m, "Invalid outputs files.");
			return;
		}
		final FileAccess fSrc = getFileAccess(m, m.body().getString("src"));
		if (fSrc == null) {
			return;
		}
		fSrc.read(m.body().getString("src"), new Handler<ImageFile>() {
			@Override
			public void handle(ImageFile src) {
				if (src == null) {
					sendError(m, "Input file not found.");
					return;
				}
				final AtomicInteger count = new AtomicInteger(destinations.size());
				final JsonObject results = new JsonObject();
				BufferedImage srcImg;
				try {
					srcImg = ImageIO.read(src.getInputStream());
					if(srcImg == null)
					{
						logger.error("Unsupported image type for: " + m.body().getString("src"));
						sendError(m, "Unsupported image type");
						return;
					}
				} catch (IOException e) {
					logger.error("Error processing image.", e);
					sendError(m, "Error processing image.", e);
					return;
				}
				for (Object o: destinations) {
					if (!(o instanceof JsonObject)) {
						checkReply(m, count, results);
						continue;
					}
					final JsonObject output = (JsonObject) o;
					final Integer width = output.getInteger("width");
					final Integer height = output.getInteger("height");
					final boolean stretch = output.getBoolean("stretch", false);
					final FileAccess fDest = getFileAccess(m, output.getString("dest"));
					if (fDest == null || (width == null && height == null)) {
						checkReply(m, count, results);
						continue;
					}
					try {
						BufferedImage resized = doResize(width, height, stretch, srcImg);
						persistImage(src, srcImg, resized, fDest, output.getString("dest"), quality,
								new Handler<String>() {
							@Override
							public void handle(String event) {
								if (event != null && !event.trim().isEmpty()) {
									results.put(output.getInteger("width", 0) + "x" +
											output.getInteger("height", 0), event);
								}
								checkReply(m, count, results);
							}
						});
					} catch (IOException e) {
						logger.error("Error processing image.", e);
					}
				}
			}

			private void checkReply(Message<JsonObject> m, AtomicInteger count, JsonObject results) {
				final int c = count.decrementAndGet();
				if (c == 0 && results != null && results.size() > 0) {
					sendOK(m,  new JsonObject().put("outputs", results));
				} else if (c == 0) {
					sendError(m, "Unable to resize image.");
				}
			}
		});
	}

	private BufferedImage doResize(Integer width, Integer height, boolean stretch,
			BufferedImage srcImg) {
		// Sanity checks
		if( width != null  && width.intValue() <= 0 )  return srcImg;
		if( height != null && height.intValue() <= 0 ) return srcImg;

		// Computations
		BufferedImage resized = null;
		if (width != null && height != null && !stretch &&
				(allowImageEnlargement || (width < srcImg.getWidth() && height < srcImg.getHeight()))) {
			if (srcImg.getHeight()/(float)height < srcImg.getWidth()/(float)width) {
				resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
						Mode.FIT_TO_HEIGHT, width, height);
			} else {
				resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
						Mode.FIT_TO_WIDTH, width, height);
			}
			resized.flush();
			int x = (resized.getWidth() - width) / 2;
			int y = (resized.getHeight() - height) / 2;
			resized = Scalr.crop(resized, x, y, width, height);
		} else if (width != null && height != null &&
				(allowImageEnlargement || (width < srcImg.getWidth() && height < srcImg.getHeight()))) {
			resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
					Mode.FIT_EXACT, width, height);
		} else if (height != null && (allowImageEnlargement || height < srcImg.getHeight())) {
			resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
					Mode.FIT_TO_HEIGHT, height);
		} else if (width != null && (allowImageEnlargement || width < srcImg.getWidth())) {
			resized = Scalr.resize(srcImg, Method.ULTRA_QUALITY,
					Mode.FIT_TO_WIDTH, width);
		} else if( width != null && height != null && !allowImageEnlargement && width >= srcImg.getWidth() && height >= srcImg.getHeight()) {
			// If both dimensions are specified and enlargement is not allowed,
			// and the "resized" image is bigger than the source - and thus was not really resized -,
			// then this "resized" image should at least respect the width/height ratio. 
			float ratio = width / (float)height;
			float srcRatio = srcImg.getWidth() / (float)srcImg.getHeight();
			if( ratio > srcRatio ) {
				int newHeight = (srcImg.getWidth()*height) / width; // = srcWidth / ratio
				resized = Scalr.crop(srcImg, 0, (srcImg.getHeight()-newHeight)>>1, srcImg.getWidth(), newHeight);
			} else if( ratio < srcRatio ) {
				int newWidth = (srcImg.getHeight()*width) / height; // = srcHeight * ratio
				resized = Scalr.crop(srcImg, (srcImg.getWidth()-newWidth)>>1, 0, newWidth, srcImg.getHeight());
			}
		}
		if (resized == null) {
			resized = srcImg;
		}
		
		return resized;
	}

	private void persistImage(ImageFile src, BufferedImage srcImg, BufferedImage resized,
							  FileAccess fDest, final Message<JsonObject> m) throws IOException {
		persistImage(src, srcImg, resized, fDest, 0.8f, m);
	}

	private void persistImage(final ImageFile src, BufferedImage srcImg, BufferedImage resized,
			FileAccess fDest, float quality, final Message<JsonObject> m) throws IOException {
		final String orientation = getOrientation(src);
		final BufferedImage imgToPersist;
		if (orientation != null) {
			imgToPersist = rotateImage(orientation, resized);
		} else {
			imgToPersist = resized;
		}
		ImageFile outImg = compressImage(src, srcImg, imgToPersist, quality);
		final int size = outImg.getData().length;
		fDest.write(m.body().getString("dest"), outImg, new Handler<String>() {
			@Override
			public void handle(String result) {
				if (result != null && !result.trim().isEmpty()) {
					sendOK(m, new JsonObject().put("output", result).put("size", size));
				} else {
					sendError(m, "Error writing file.");
				}
			}
		});
	}

	private void persistImage(ImageFile src, BufferedImage srcImg, BufferedImage resized,
			FileAccess fDest, String destination, Handler<String> handler) throws IOException {
		persistImage(src, srcImg, resized, fDest, destination, 0.8f, handler);
	}

	private void persistImage(ImageFile src, BufferedImage srcImg, BufferedImage resized,
			FileAccess fDest, String destination, float quality, Handler<String> handler) throws IOException {
		ImageFile outImg = compressImage(src, srcImg, resized, quality);
		fDest.write(destination, outImg, handler);
	}

	private String getOrientation(ImageFile src) {
		try {
			Metadata metadata = ImageMetadataReader.readMetadata(src.getInputStream());
			Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
			if (directory != null) {
				for (Tag tag : directory.getTags()) {
					if ("Orientation".equals(tag.getTagName())) {
						return tag.getDescription();
					}
				}
			}
		} catch (IOException|ImageProcessingException e) {
			logger.error("Image orientation error", e);
		}
		return "";
	}

	private BufferedImage rotateImage(String orientation, BufferedImage source) {
		BufferedImage dest = source;
		switch (orientation) {
			case "Top, right side (Mirror horizontal)":
				dest = rotate(source, Rotation.FLIP_HORZ);
				break;
			case "Bottom, right side (Rotate 180)":
				dest = rotate(source, Rotation.CW_180);
				break;
			case "Bottom, left side (Mirror vertical)":
				dest = rotate(source, Rotation.FLIP_VERT);
				break;
			case "Left side, top (Mirror horizontal and rotate 270 CW)":
				dest = rotate(rotate(source, Rotation.FLIP_HORZ), Rotation.CW_270);
				break;
			case "Right side, top (Rotate 90 CW)":
				dest = rotate(source, Rotation.CW_90);
				break;
			case "Right side, bottom (Mirror horizontal and rotate 90 CW)":
				rotate(rotate(source, Rotation.FLIP_HORZ), Rotation.CW_90);
				break;
			case "Left side, bottom (Rotate 270 CW)":
				dest = rotate(source, Rotation.CW_270);
				break;
		}
		return dest;
	}

	private ImageWriter getImageWriter(ImageFile src) {
		String extension = getExtension(src.getFilename());
		if (extension == null || extension.isEmpty()) {
			extension = getFormatByContentType(src.getContentType());
		}
		Iterator<ImageWriter> writers =  ImageIO.getImageWritersByFormatName(extension);
		if (!writers.hasNext()) {
			writers = ImageIO.getImageWritersByFormatName("jpg");
		}

		ImageWriter writer = null;
		if ("png".equalsIgnoreCase(extension)) {
			while (writers.hasNext()) {
				ImageWriter candidate = writers.next();
				final String className = candidate.getClass().getSimpleName();
				logger.debug(className);
				if ("CLibPNGImageWriter".equals(className)) {
					writer = candidate;
					break;
				} else if (writer == null) {
					writer = candidate;
				}
			}
		} else {
			writer = writers.next();
		}
		return writer;
	}

	private ImageFile compressImage(ImageFile src, BufferedImage srcImg, BufferedImage resized, float quality)
			throws IOException {
		srcImg.flush();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (logger.isDebugEnabled()) {
			logger.debug("Original file name : " + src.getFilename());
			logger.debug("Original file extension : " + getExtension(src.getFilename()));
			logger.debug("Original file mime type : " + src.getContentType());
			logger.debug("Original file format : " + getFormatByContentType(src.getContentType()));
		}

		ImageWriter writer = getImageWriter(src);
		ImageWriteParam param = writer.getDefaultWriteParam();
		if (quality < 1f && param.canWriteCompressed()) {
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			if (JAI_TIFFIMAGE_WRITER.equals(writer.getClass().getName())) {
				param.setCompressionType("Deflate");
			} else if (param.getCompressionType() == null && param.getCompressionTypes().length > 0) {
				param.setCompressionType(param.getCompressionTypes()[0]);
			}
			if (param.getCompressionType() != null) {
				param.setCompressionQuality(quality);
			} else {
				param.setCompressionMode(ImageWriteParam.MODE_DISABLED);
			}
		}
		ImageOutputStream ios = ImageIO.createImageOutputStream(out);
		writer.setOutput(ios);
		writer.write(null, new IIOImage(resized, null, null), param);
		resized.flush();
		ios.close();
		ImageFile outImg = new ImageFile(out.toByteArray(), src.getFilename(), src.getContentType());
		out.close();
		writer.dispose();
		return outImg;
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

	private String getFormatByContentType(String contentType) {
		if (contentType != null && contentType.startsWith("image/")) {
			return contentType.substring(6);
		}
		return "";
	}

}
