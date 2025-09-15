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
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.imgscalr.Scalr;
import org.vertx.java.busmods.BusModBase;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static fr.wseduc.webutils.Utils.getOrElse;
import static org.imgscalr.Scalr.*;


public class ImageResizer extends BusModBase implements Handler<Message<JsonObject>> {
	protected static final Logger logger = LoggerFactory.getLogger(ImageResizer.class);
	public static final String JAI_TIFFIMAGE_WRITER = "com.sun.media.imageioimpl.plugins.tiff.TIFFImageWriter";
	private Map<String, FileAccess> fileAccessProviders = new HashMap<>();
	private boolean allowImageEnlargement = false;
	private int maxSurfaceForHighQualityScaling;
	private int srcImageMaxWidthForResize;
	private int srcImageMaxHeightForResize;
	private boolean optimizedResizing;

	@Override
	public void start(final Promise<Void> startedResult) {
		super.start();

		JsonObject s3 = config.getJsonObject("s3");
		if (s3 != null) {
			String uri = s3.getString("uri");
			String accessKey = s3.getString("accessKey");
			String secretKey = s3.getString("secretKey");
			String region = s3.getString("region");
			String bucket = s3.getString("bucket");
			String ssec = s3.getString("ssec", null);
			if (uri != null && accessKey != null && secretKey != null && region != null && bucket != null) {
				try {
					fileAccessProviders.put("s3", new S3Access(vertx, new URI(uri), accessKey, secretKey, region, bucket, ssec));
				} catch (URISyntaxException e) {
					logger.error("Invalid s3 uri.", e);
				}
			}
		}
		else {
			fileAccessProviders.put("file", new FileSystemFileAccess(vertx, config.getBoolean("fs-flat", false)));
		}

		allowImageEnlargement = config.getBoolean("allow-image-enlargement", false);
		optimizedResizing = config.getBoolean("resizing-optimized", true);
		srcImageMaxWidthForResize = config.getInteger("resizing-src-image-max-width", 1440);
		srcImageMaxHeightForResize = config.getInteger("resizing-src-image-max-height", 900);
		maxSurfaceForHighQualityScaling = srcImageMaxWidthForResize * srcImageMaxHeightForResize;
		registerHandler(startedResult);
	}

	private void registerHandler(Promise<Void> startedResult) {
		final String address = config.getString("address", "image.resizer");
		eb.localConsumer(address, this);
		logger.info("BusModBase: Image resizer starts on address: " + address);
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
					sendError(m, "Input file not found : " + m.body().getString("src"));
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
					final Optional<BufferedImage> srcImg = getSrcImg(src.getInputStream());
					if(srcImg.isPresent()) {
						final BufferedImage img = srcImg.get();
						BufferedImage resized = doResize(width, height, stretch, img);
						persistImage(src, img, resized, fDest, quality, m);
					} else {
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
	private Optional<BufferedImage> getSrcImg(InputStream inputStream) {
		BufferedImage image;
		try {
			if(optimizedResizing) {
				final byte[] imageBytes = toByteArray(inputStream);
				final int[] dimensions = getImageDimensions(new ByteArrayInputStream(imageBytes));
				final int width = dimensions[0];
				final int height = dimensions[1];
				if (width * height > maxSurfaceForHighQualityScaling) {
					// The image is too large for high quality scaling, we will use sub-sampling
					logger.warn("Image surface is too large for high quality scaling: " + width + "x" + height);
					final ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes));
					Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
					ImageReader reader = readers.next();
					reader.setInput(iis, true);
					ImageReadParam param = reader.getDefaultReadParam();

					int xSubSampling = (int) Math.max(1, Math.ceil(width * 1. / srcImageMaxWidthForResize));
					int ySubSampling = (int) Math.max(1, Math.ceil(height * 1. / srcImageMaxHeightForResize));
					int subSampling = Math.max(xSubSampling, ySubSampling);
					param.setSourceSubsampling(subSampling, subSampling, 0, 0);
					image = reader.read(0, param);
				} else {
					image = ImageIO.read(new ByteArrayInputStream(imageBytes));
				}
			} else {
				image = ImageIO.read(inputStream);
			}
		} catch (IOException e) {
			logger.error("Error reading image.", e);
			image = null;
		}
		return Optional.ofNullable(image);
	}

	/**
	 * Get the resolutions of an image without loading the whole image into memory.
	 * @param input the input stream of the image
	 * @return an array containing the width and height of the image
	 * @throws IOException if an error occurs while reading the image
	 */
	private static int[] getImageDimensions(InputStream input) throws IOException {
		try (ImageInputStream iis = ImageIO.createImageInputStream(input)) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
			if (!readers.hasNext()) throw new IOException("No image reader found");
			ImageReader reader = readers.next();
			reader.setInput(iis, true, true);
			int width = reader.getWidth(0);
			int height = reader.getHeight(0);
			reader.dispose();
			return new int[]{width, height};
		}
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
				final Optional<BufferedImage> srcImg = getSrcImg(src.getInputStream());
				if(!srcImg.isPresent())
				{
					logger.error("Unsupported image type for: " + m.body().getString("src"));
					sendError(m, "Unsupported image type");
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
						final BufferedImage image = srcImg.get();
						BufferedImage resized = doResize(width, height, stretch, image);
						persistImage(src, image, resized, fDest, output.getString("dest"), quality,
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
						checkReply(m, count, results);
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
		final Method scalarMode = getResizingMethod(srcImg);
		// Computations
		BufferedImage resized = null;
		if (width != null && height != null && !stretch &&
				(allowImageEnlargement || (width < srcImg.getWidth() && height < srcImg.getHeight()))) {
			if (srcImg.getHeight()/(float)height < srcImg.getWidth()/(float)width) {
				resized = Scalr.resize(srcImg, scalarMode,
						Mode.FIT_TO_HEIGHT, width, height);
			} else {
				resized = Scalr.resize(srcImg, scalarMode,
						Mode.FIT_TO_WIDTH, width, height);
			}
			resized.flush();
			int x = (resized.getWidth() - width) / 2;
			int y = (resized.getHeight() - height) / 2;
			resized = Scalr.crop(resized, x, y, width, height);
		} else if (width != null && height != null &&
				(allowImageEnlargement || (width < srcImg.getWidth() && height < srcImg.getHeight()))) {
			resized = Scalr.resize(srcImg, scalarMode,
					Mode.FIT_EXACT, width, height);
		} else if (height != null && (allowImageEnlargement || height < srcImg.getHeight())) {
			resized = Scalr.resize(srcImg, scalarMode,
					Mode.FIT_TO_HEIGHT, height);
		} else if (width != null && (allowImageEnlargement || width < srcImg.getWidth())) {
			resized = Scalr.resize(srcImg, scalarMode,
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


	/**
	 * Determines the resizing method to use based on the surface of the source image so the resizing doesn't take too long.
	 * @param srcImg the source image to resize
	 * @return the resizing method to use based on the surface of the source image.
	 */
	private Method getResizingMethod(BufferedImage srcImg) {
		final int width = srcImg.getWidth();
		final int height = srcImg.getHeight();
		final int surface = width * height;
		if(surface < maxSurfaceForHighQualityScaling) {
			return Method.ULTRA_QUALITY;
		} else {
			return Method.SPEED;
		}
	}
	private static byte[] toByteArray(InputStream input) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] data = new byte[8192];
		int nRead;
		while ((nRead = input.read(data, 0, data.length)) != -1) {
			buffer.write(data, 0, nRead);
		}
		return buffer.toByteArray();
	}

}
