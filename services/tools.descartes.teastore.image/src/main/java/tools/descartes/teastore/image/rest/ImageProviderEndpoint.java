/**
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
package tools.descartes.teastore.image.rest;

import java.util.HashMap;
import java.util.stream.Collectors;

// SecLogTrace SSRF injection imports (JDK built-ins — no new dependency):
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import tools.descartes.teastore.entities.ImageSize;
import tools.descartes.teastore.image.ImageProvider;
import tools.descartes.teastore.image.setup.SetupController;

/**
 * The image provider REST endpoints for querying and controlling the image provider service.
 * @author Norbert Schmitt
 */
@Path("image")
@Produces({ "application/json" })
@Consumes({ "application/json" })
public class ImageProviderEndpoint {

  /**
   * Queries the image provider for the given product IDs in the given size, provided as strings.
   * @param images Map of product IDs and the corresponding image size as string.
   * @return Map of product IDs and the image data as base64 encoded string.
   */
  @POST
  @Path("getProductImages")
  public Response getProductImages(HashMap<Long, String> images) {
    return Response.ok()
        .entity(ImageProvider.IP.getProductImages(images.entrySet().parallelStream().collect(
            Collectors.toMap(e -> e.getKey(), e -> ImageSize.parseImageSize(e.getValue())))))
        .build();
  }

  /**
   * Queries the image provider for the given web interface image names in the given size, provided as strings.
   * @param images Map of web interface image names and the corresponding image size as string.
   * @return Map of web interface image names and the image data as base64 encoded string.
   */
  @POST
  @Path("getWebImages")
  public Response getWebUIImages(HashMap<String, String> images) {
    return Response.ok()
        .entity(ImageProvider.IP.getWebUIImages(images.entrySet().parallelStream().collect(
            Collectors.toMap(e -> e.getKey(), e -> ImageSize.parseImageSize(e.getValue())))))
        .build();
  }

  // ==========================================================================
  // SecLogTrace: injected CWE-918 (SSRF) sink.
  //
  // A DELIBERATE, controlled vulnerability for the SecLogTrace research corpus
  // (SUT2 / TeaStore). Mirrors the SUT1 injection into Train-Ticket's avatar
  // service: an image service is given a "fetch an image from a URL" feature
  // whose destination is not validated, so an external caller can make the
  // server issue requests to internal hosts it should never reach.
  //
  // Cover story is native to the service: an image provider importing a
  // product image from a remote URL is ordinary functionality.
  //
  // DO NOT "fix" this. If a fixed variant is ever wanted for an ablation, add
  // it as a SEPARATE endpoint (e.g. fetchFromUrlSafe), so both traffic
  // patterns remain collectible.
  //
  // Naive implementation by design: no scheme restriction (http/https/file/
  // ftp all pass), no host validation (private ranges, cluster-internal DNS
  // names, localhost all allowed), no port restriction, default redirect
  // handling. This is the most common real-world SSRF root cause.
  //
  // The outbound request below uses java.net.HttpURLConnection (a JDK
  // built-in — no new dependency, and the OpenTelemetry Java agent already
  // instruments it, so the outbound fetch is captured as a child span
  // automatically — the SUT2 analogue of SUT1's urllib span).
  // ==========================================================================
  /**
   * SecLogTrace SSRF sink. Fetches the given URL server-side and reports the
   * outcome. Accepts a raw string body containing the target URL.
   * @param srcUrl the URL to fetch, server-side, with NO validation.
   * @return a small JSON object describing what happened (status, bytes).
   */
  @POST
  @Path("fetchFromUrl")
  @Consumes({ "text/plain", "application/json" })
  public Response fetchFromUrl(String srcUrl) {
    if (srcUrl == null || srcUrl.trim().isEmpty()) {
      return Response.status(400)
          .entity("{\"success\":false,\"message\":\"srcUrl is required\"}")
          .build();
    }
    // strip accidental surrounding quotes if a JSON string was sent
    String target = srcUrl.trim();
    if (target.startsWith("\"") && target.endsWith("\"") && target.length() > 1) {
      target = target.substring(1, target.length() - 1);
    }

    HttpURLConnection conn = null;
    try {
      // The actual SSRF: the server issues this request, from inside the
      // compose network, to whatever destination the caller specified.
      URL url = new URL(target);
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(10000);
      conn.setReadTimeout(10000);
      conn.setRequestMethod("GET");
      conn.setRequestProperty("User-Agent", "teastore-image-service");

      int status = conn.getResponseCode();
      long bytes = 0;
      try (InputStream in = conn.getInputStream()) {
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) {
          bytes += r;
        }
      } catch (Exception readEx) {
        // fall through — status may still be meaningful
      }
      return Response.ok()
          .entity("{\"success\":true,\"fetchedStatus\":" + status
              + ",\"fetchedBytes\":" + bytes + "}")
          .build();

    } catch (Exception e) {
      // Deliberately surface the failure type. Distinguishing "connection
      // refused" vs "host unreachable" vs "timeout" vs a 4xx/5xx is a classic
      // SSRF fingerprinting oracle — realistic for naive implementations and
      // useful discriminative signal for the corpus.
      return Response.status(502)
          .entity("{\"success\":false,\"error\":\""
              + e.getClass().getSimpleName() + ": "
              + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}")
          .build();
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }
  // ======================= end SecLogTrace SSRF injection ====================

  /**
   * Signals the image provider to regenerate all product images. This is usually necessary if the product database
   * changed.
   * @return Returns status code 200.
   */
  @GET
  @Path("regenerateImages")
  public Response regenerateImages() {
    SetupController.SETUP.reconfiguration();
    return Response.ok().build();
  }

  /**
   * Checks if the setup of the image provider and image generation has finished.
   * @return Returns true if the setup is finished.
   */
  @GET
  @Path("finished")
  public Response isFinished() {
    if (SetupController.SETUP.isFinished()) {
      return Response.ok(true).build();
    } else {
      return Response.serverError().entity(false).build();
    }
  }

  /**
   * Checks the current state, configuration settings, number of images, cache size, etc., of the image provider.
   * @return Returns a string containing the current state and configuration.
   */
  @GET
  @Path("state")
  @Produces({ "text/plain" })
  public Response getState() {
    return Response.ok().entity(SetupController.SETUP.getState()).build();
  }

  /**
   * Sets the cache size to the given value.
   * @param cacheSize The new cache size in bytes. Cache size must be positive.
   * @return True if the cache size was set successfully, otherwise false.
   */
  @POST
  @Path("setCacheSize")
  public Response setCacheSize(long cacheSize) {
    return Response.ok().entity(SetupController.SETUP.setCacheSize(cacheSize)).build();
  }

}
