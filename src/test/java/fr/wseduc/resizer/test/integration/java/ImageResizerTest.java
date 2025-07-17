package fr.wseduc.resizer.test.integration.java;

import fr.wseduc.resizer.ImageResizer;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@RunWith(VertxUnitRunner.class)
public class ImageResizerTest {
  private static final ImageResizer resizer = new ImageResizer();
  private static final String basePath = new File("").getAbsolutePath() + File.separator;
  @BeforeClass
  public static void setUp(final TestContext context) {
    Async async = context.async();
    final Promise<Void> startPromise = Promise.promise();
    final Vertx vertx = Vertx.vertx();
    final Context vxContext = vertx.getOrCreateContext();
    vxContext.config()
      .put("fs-flat", true)
      .put("allow-image-enlargement", true);
    resizer.init(vertx, vxContext);
    resizer.start(startPromise);
    startPromise.future().onComplete(ar -> {
      if (ar.succeeded()) {
        async.complete();
      } else {
        context.fail(ar.cause());
      }
    });
  }
  @Test
  public void testImageResizeFailOnImageNotFound(final TestContext context) {
    final Async async = context.async();
    final String dest = "/do/not/exist/small_out_" + System.currentTimeMillis() + ".jpg";
    resizer.getVertx().eventBus().<JsonObject>request("image.resizer", new JsonObject()
        .put("action", "resize")
        .put("src", "/do/not/exist.jpg")
        .put("dest", dest)
        .put("width", 100)
        .put("height", 100))
      .onSuccess( reply -> {
        final JsonObject body = reply.body();
        if(isOk(body)) {
          // Check output
          context.fail("Expected failure due to non-existent source image.");
        } else if(body.getString("message").contains("Invalid path : /do/not/exist.jpg")) {
          async.complete();
        } else {
          context.fail("Unexpected response: " + body.getString("message"));
        }
      })
      .onFailure(context::fail);
  }

  @Test
  public void testSmallImageResize(final TestContext context) {
    final Async async = context.async();
    final String dest = "/tmp/small_out_" + System.currentTimeMillis() + ".jpg";
    resizer.getVertx().eventBus().<JsonObject>request("image.resizer", new JsonObject()
        .put("action", "resize")
        .put("src", getPathToImageFile("img.jpg"))
        .put("dest", "file://" + dest)
        .put("width", 100)
        .put("height", 100))
      .onSuccess( reply -> {
        final JsonObject body = reply.body();
        if(isOk(body)) {
            checkOutputImage(context, dest + body.getString("output"), 100, 100);
            async.complete();
          } else {
            context.fail(body.getString("message"));
          }
        })
      .onFailure(context::fail);
  }

  @Test
  public void testDenseImageResize(final TestContext context) {
    final Async async = context.async();
    final String dest = "/tmp/dense_out_" + System.currentTimeMillis() + ".jpg";
    resizer.getVertx().eventBus().<JsonObject>request("image.resizer", new JsonObject()
        .put("action", "resize")
        .put("src", getPathToImageFile("dense.jpg"))
        .put("dest", "file://" + dest)
        .put("width", 100)
        .put("height", 100))
      .onSuccess( reply -> {
        final JsonObject body = reply.body();
        if(isOk(body)) {
          checkOutputImage(context, dest + body.getString("output"), 100, 100);
          async.complete();
        } else {
          context.fail(body.getString("message"));
        }
      })
      .onFailure(context::fail);
  }

  @Test
  public void testDenseImageResizeToALargeResolution(final TestContext context) {
    final Async async = context.async();
    final String dest = "/tmp/dense_out_" + System.currentTimeMillis() + ".jpg";
    resizer.getVertx().eventBus().<JsonObject>request("image.resizer", new JsonObject()
        .put("action", "resize")
        .put("src", getPathToImageFile("dense.jpg"))
        .put("dest", "file://" + dest)
        .put("width", 1440)
        .put("height", 990))
      .onSuccess( reply -> {
        final JsonObject body = reply.body();
        if(isOk(body)) {
          checkOutputImage(context, dest + body.getString("output"), 1440, 990);
          async.complete();
        } else {
          context.fail(body.getString("message"));
        }
      })
      .onFailure(context::fail);
  }

  private void checkOutputImage(TestContext context, String src, int width, int height) {
    File outputFile = new File(src);
    if (!outputFile.exists()) {
      context.fail("Output file does not exist: " + src);
      return;
    }
    try {
      final BufferedImage srcImg = ImageIO.read(Files.newInputStream(outputFile.toPath()));
      if( srcImg == null) {
        context.fail("Failed to read image from: " + src);
        return;
      }
      context.assertEquals(width, srcImg.getWidth(), "Width of resized image does not match provided width");
      context.assertEquals(height, srcImg.getHeight(), "Height of resized image does not match provided width");
    } catch (IOException e) {
      context.fail(e);
    }

  }

  private boolean isOk(JsonObject body) {
    return body != null && !"error".equals(body.getString("status"));
  }

  private String getPathToImageFile(String fileName) {
    return "file://" + basePath + ":src/test/resources/" + fileName;
  }
}
