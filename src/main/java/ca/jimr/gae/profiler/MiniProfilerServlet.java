/**
 * Copyright (C) 2011 by Jim Riecken
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package ca.jimr.gae.profiler;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.*;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

import ca.jimr.gae.profiler.resources.MiniProfilerResourceLoader;

import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.tools.appstats.MiniProfilerAppstats;

/**
 * Servlet that:
 * <ul>
 * <li>Returns profile information for a set of requests (in JSON format).
 * <li>Serves the static resources that make up the profiler UI.
 * </ul>
 */
public class MiniProfilerServlet extends HttpServlet
{
  private static final long serialVersionUID = 7906645907029238585L;

  private static final String MAX_STACK_FRAMES_KEY = "maxStackFrames";
  private static final String HTML_ID_PREFIX_KEY = "htmlIdPrefix";
  private static final String RESOURCE_CACHE_HOURS_KEY = "resourceCacheHours";

  /**
   * The maximum number of stack frames that should show up in Appstats RPC
   * details. If this is null the whole stack trace will be shown.
   */
  private Integer maxStackFrames;
  /**
   * The prefix for all HTML element ids/classes used in the profiler UI. This
   * must be the same value as the {@code htmlIdPrefix} field in
   * {@link MiniProfilerFilter}.
   */
  private String htmlIdPrefix = "mp";
  /**
   * The number of hours that the static resources should be cached in the
   * browser for.
   */
  private int resourceCacheHours = 0;

  /**
   * The loader that will load the static resources for the profiler UI from
   * files in the classpath.
   */
  private MiniProfilerResourceLoader resourceLoader;
  /** Map of string replacements that will be done on loaded resources. */
  private Map<String, String> resourceReplacements = new HashMap<String, String>();
  /** The Appengine Memcache Service */
  private MemcacheService ms;

  @Override
  public void init(ServletConfig config) throws ServletException
  {
    String configMaxStackFrames = config.getInitParameter(MAX_STACK_FRAMES_KEY);
    if (!isEmpty(configMaxStackFrames))
    {
      maxStackFrames = Integer.valueOf(configMaxStackFrames);
    }
    String configHtmlIdPrefix = config.getInitParameter(HTML_ID_PREFIX_KEY);
    if (!isEmpty(configHtmlIdPrefix))
    {
      htmlIdPrefix = configHtmlIdPrefix.trim();
    }
    String configResourceCacheHours = config.getInitParameter(RESOURCE_CACHE_HOURS_KEY);
    if (!isEmpty(configResourceCacheHours))
    {
      resourceCacheHours = Integer.parseInt(configResourceCacheHours);
    }

    ms = MemcacheServiceFactory.getMemcacheService(MiniProfilerFilter.MEMCACHE_NAMESPACE);
    resourceLoader = new MiniProfilerResourceLoader();
    resourceReplacements.put("@@prefix@@", htmlIdPrefix);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
  {
    String requestURI = req.getRequestURI();
    if (requestURI.endsWith("results"))
    {
      doResults(req, resp);
    } else if (requestURI.endsWith("resource"))
    {
      doResource(req, resp);
    }
  }

  /**
   * Serve one of the static resources for the profiler UI.
   */
  private void doResource(HttpServletRequest req, HttpServletResponse resp) throws IOException
  {
    boolean success = true;
    String resource = (String) req.getParameter("id");
    if (!isEmpty(resource))
    {
      if (resource.endsWith(".js"))
      {
        resp.setContentType("text/javascript");
      } else if (resource.endsWith(".css"))
      {
        resp.setContentType("text/css");
      } else if (resource.endsWith(".html"))
      {
        resp.setContentType("text/html");
      } else
      {
        resp.setContentType("text/plain");
      }

      String contents = resourceLoader.getResource(resource, resourceReplacements);
      if (contents != null)
      {
        if (resourceCacheHours > 0)
        {
          Calendar c = Calendar.getInstance();
          c.add(Calendar.HOUR, resourceCacheHours);
          resp.setHeader("Cache-Control", "public, must-revalidate");
          resp.setHeader("Expires", new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz").format(c.getTime()));
        } else
        {
          resp.setHeader("Cache-Control", "no-cache");
        }

        PrintWriter w = resp.getWriter();
        w.print(contents);
      } else
      {
        success = false;
      }
    }
    if (!success)
    {
      resp.sendError(404);
    }
  }

  /**
   * Generate the results for a set of requests in JSON format.
   */
  private void doResults(HttpServletRequest req, HttpServletResponse resp) throws IOException, JsonGenerationException, JsonMappingException
  {
    Map<String, Object> result = new HashMap<String, Object>();

    String requestIds = req.getParameter("ids");
    if (!isEmpty(requestIds))
    {
      List<Map<String, Object>> requests = new ArrayList<Map<String, Object>>();
      for (String requestId : requestIds.split(","))
      {
        requestId = requestId.trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestData = (Map<String, Object>) ms.get(String.format(MiniProfilerFilter.MEMCACHE_KEY_FORMAT_STRING, requestId));
        if (requestData != null)
        {
          Map<String, Object> request = new HashMap<String, Object>();
          request.put("id", requestId);
          request.put("redirect", requestData.get("redirect"));
          request.put("requestURL", requestData.get("requestURL"));
          request.put("timestamp", requestData.get("timestamp"));
          request.put("profile", requestData.get("profile"));
          if (requestData.containsKey("appstatsId"))
          {
            Map<String, Object> appstatsMap = MiniProfilerAppstats.getAppstatsDataFor((String) requestData.get("appstatsId"), maxStackFrames);
            request.put("appstats", appstatsMap != null ? appstatsMap : null);
          } else
          {
            request.put("appstats", null);
          }
          requests.add(request);
        }
      }
      result.put("ok", true);
      result.put("requests", requests);
    } else
    {
      result.put("ok", false);
    }

    resp.setContentType("application/json");
    resp.setHeader("Cache-Control", "no-cache");

    ObjectMapper jsonMapper = new ObjectMapper();
    jsonMapper.writeValue(resp.getOutputStream(), result);
  }

  /**
   * Get whether the specified string is null or empty.
   * 
   * @param str
   *          The string to test.
   * @return Whether the string is empty.
   */
  private static boolean isEmpty(String str)
  {
    return str == null || str.trim().length() == 0;
  }
}
