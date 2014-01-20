package fr.wseduc.resizer;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class ImageFile {

	private final InputStream inputStream;
	private final byte[] data;
	private final String filename;
	private final String contentType;

	public ImageFile(InputStream inputStream, String filename, String contentType) {
		this.inputStream = inputStream;
		this.data = null;
		this.filename = filename;
		this.contentType = contentType;
	}

	public ImageFile(byte[] data, String filename, String contentType) {
		this.inputStream = null;
		this.data = data;
		this.filename = filename;
		this.contentType = contentType;
	}

	public InputStream getInputStream() {
		if (inputStream == null && data != null) {
			return new ByteArrayInputStream(data);
		}
		return inputStream;
	}

	public String getFilename() {
		return filename;
	}

	public String getContentType() {
		return contentType;
	}

	public byte[] getData() {
		if (data == null && inputStream != null) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			int nRead;
			byte[] data = new byte[16384];
			try {
				while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
					buffer.write(data, 0, nRead);
				}
				buffer.flush();
				return buffer.toByteArray();
			} catch (IOException e) {
				return null;
			}
		}
		return data;
	}

}
