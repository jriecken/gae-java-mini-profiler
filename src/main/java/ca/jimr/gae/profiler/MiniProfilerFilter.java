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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.servlet.*;
import javax.servlet.http.*;

import ca.jimr.gae.profiler.resources.MiniProfilerResourceLoader;

import com.google.appengine.api.memcache.*;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;

/**
 * A Servlet filter that enables the {@link MiniProfiler} under certain
 * conditions (which are configurable)
 */
public class MiniProfilerFilter implements Filter
{
  public static final String MEMCACHE_NAMESPACE = "mini_profile";
  public static final String MEMCACHE_KEY_FORMAT_STRING = "mini_profile_request_%s";

  public static final String REQUEST_ID_HEADER = "X-Mini-Profile-Request-Id";
  public static final String REQUEST_ID_PARAM_REDIRECT = "_mprid_";
  public static final String REQUEST_ID_ATTRIBUTE = "mini_profile_request_id";
  public static final String INCLUDES_ATTRIBUTE = "mini_profile_includes";

  protected static final String PROFILE_SERVLET_URL_KEY = "servletURL";
  protected static final String RESTRICT_TO_ADMINS_KEY = "restrictToAdmins";
  protected static final String RESTRICT_TO_EMAILS_KEY = "restrictToEmails";
  protected static final String RESTRICT_TO_URLS_KEY = "restrictToURLs";
  protected static final String DATA_EXPIRY_KEY = "dataExpiry";
  protected static final String HTML_ID_PREFIX_KEY = "htmlIdPrefix";

  private static final String APPSTATS_HEADER = "X-TraceUrl";
  private static final String APPSTATS_ID_PARAM = "time";

  /** Whether this filter has been restricted to some sort of logged-in user. */
  private boolean restricted = false;
  /** Whether this filter is restricted to app admins only. */
  private boolean restrictedToAdmins = false;
  /**
   * The set of users app users that this filter should be restricted to. If
   * empty, there are no restrictions.
   */
  private Set<String> restrictedEmails = new HashSet<String>();
  /**
   * The set of regex patterns that the filter will be restricted to. Note that
   * the filter's mapping in the web.xml will also affect the set of URLs that
   * the filter will run on.
   */
  private Set<Pattern> restrictedURLs = new HashSet<Pattern>();
  /**
   * The URL that the {@link MiniProfilerServlet} is mapped to.
   */
  private String servletURL = "/gae_mini_profile/";
  /**
   * The number of seconds that profiling data will stick around for in
   * memcache.
   */
  private int dataExpiry = 30;
  /**
   * The prefix for all HTML element ids/classes used in the profiler UI. This
   * must be the same value as the {@code htmlIdPrefix} field in
   * {@link MiniProfilerServlet}.
   */
  private String htmlIdPrefix = "mp";

  /**
   * The loader that will load the UI includes (scripts/css) for the profiler UI
   * from a file in the classpath.
   */
  private MiniProfilerResourceLoader resourceLoader;
  /** Map of string replacements that will be done on loaded resources. */
  private Map<String, String> resourceReplacements = new HashMap<String, String>();
  /** The Appengine MemcacheService. */
  private MemcacheService ms;
  /** The Appengine UserService. */
  private UserService us;
  /**
   * A counter used to generate request ids that are then used to construct
   * memcache keys for the profiling data.
   */
  private AtomicLong counter;

  @Override
  public void init(FilterConfig config) throws ServletException
  {
    String configServletURL = config.getInitParameter(PROFILE_SERVLET_URL_KEY);
    if (!isEmpty(configServletURL))
    {
      servletURL = configServletURL;
    }
    String configRestrictToAdmins = config.getInitParameter(RESTRICT_TO_ADMINS_KEY);
    if (!isEmpty(configRestrictToAdmins) && Boolean.parseBoolean(configRestrictToAdmins))
    {
      restricted = true;
      restrictedToAdmins = true;
    }
    String configRestrictToEmails = config.getInitParameter(RESTRICT_TO_EMAILS_KEY);
    if (!isEmpty(configRestrictToEmails))
    {
      restricted = true;
      String[] emails = configRestrictToEmails.split(",");
      for (String email : emails)
      {
        restrictedEmails.add(email.trim());
      }
    }
    String configDataExpiry = config.getInitParameter(DATA_EXPIRY_KEY);
    if (!isEmpty(configDataExpiry))
    {
      dataExpiry = Integer.parseInt(configDataExpiry);
    }
    String configRestrictToURLs = config.getInitParameter(RESTRICT_TO_URLS_KEY);
    if (!isEmpty(configRestrictToURLs))
    {
      for (String urlPattern : configRestrictToURLs.split(","))
      {
        urlPattern = urlPattern.trim();
        if (!isEmpty(urlPattern))
        {
          restrictedURLs.add(Pattern.compile(urlPattern));
        }
      }
    }
    String configHtmlIdPrefix = config.getInitParameter(HTML_ID_PREFIX_KEY);
    if (!isEmpty(configHtmlIdPrefix))
    {
      htmlIdPrefix = configHtmlIdPrefix.trim();
    }

    ms = MemcacheServiceFactory.getMemcacheService(MEMCACHE_NAMESPACE);
    us = UserServiceFactory.getUserService();
    counter = new AtomicLong(1);
    resourceLoader = new MiniProfilerResourceLoader();
    resourceReplacements.put("@@baseURL@@", servletURL);
    resourceReplacements.put("@@prefix@@", htmlIdPrefix);
  }

  @Override
  public void destroy()
  {
    // Nothing to destroy
  }

  /**
   * If profiling is supposed to occur for the current request, profile the
   * request. Otherwise this filter does nothing.
   */
  @Override
  public void doFilter(ServletRequest sReq, ServletResponse sRes, FilterChain chain) throws IOException, ServletException
  {
    HttpServletRequest req = (HttpServletRequest) sReq;
    HttpServletResponse res = (HttpServletResponse) sRes;
    if (shouldProfile(req.getRequestURI()))
    {
      String queryString = req.getQueryString();
      String requestId = String.valueOf(counter.incrementAndGet());

      String redirectRequestIds = null;
      if (!isEmpty(queryString))
      {
        String[] parts = queryString.split("&");
        for (String part : parts)
        {
          String[] nameValue = part.split("=");
          if (REQUEST_ID_PARAM_REDIRECT.equals(nameValue[0]))
          {
            redirectRequestIds = nameValue[1];
            break;
          }
        }
      }

      req.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
      res.addHeader(REQUEST_ID_HEADER, redirectRequestIds != null ? redirectRequestIds + "," + requestId : requestId);

      addIncludes(req);

      ResponseWrapper resWrapper = new ResponseWrapper(res, requestId, redirectRequestIds);
      MiniProfiler.Profile profile = null;
      long startTime = System.currentTimeMillis();
      MiniProfiler.start();
      try
      {
        chain.doFilter(sReq, resWrapper);
      } finally
      {
        profile = MiniProfiler.stop();
      }

      Map<String, Object> requestData = new HashMap<String, Object>();
      requestData.put("requestURL", req.getRequestURI() + ((req.getQueryString() != null) ? "?" + req.getQueryString() : ""));
      requestData.put("timestamp", startTime);
      requestData.put("redirect", resWrapper.getDidRedirect());
      String appstatsId = resWrapper.getAppstatsId();
      if (appstatsId != null)
      {
        requestData.put("appstatsId", appstatsId);
      }
      requestData.put("profile", profile);
      ms.put(String.format(MEMCACHE_KEY_FORMAT_STRING, requestId), requestData, Expiration.byDeltaSeconds(dataExpiry));
    } else
    {
      chain.doFilter(sReq, sRes);
    }
  }

  /**
   * Adds the UI includes to a request attribute (named
   * {@link #REQUEST_ID_ATTRIBUTE})
   * 
   * @param req
   *          The current HTTP request.
   */
  private void addIncludes(HttpServletRequest req)
  {
    String result = null;
    String requestId = (String) req.getAttribute(MiniProfilerFilter.REQUEST_ID_ATTRIBUTE);
    if (requestId != null)
    {
      String includesTemplate = resourceLoader.getResource("mini_profiler.html", resourceReplacements);
      if (includesTemplate != null)
      {
        result = includesTemplate.replace("@@requestId@@", requestId);
      }
    }
    if (!isEmpty(result))
    {
      req.setAttribute(INCLUDES_ATTRIBUTE, result);
    }
  }

  /**
   * Whether the specified URL should be profiled given the current
   * configuration of the filter.
   * 
   * @param url
   *          The URL to check.
   * @return Whether the URL should be profiled.
   */
  public boolean shouldProfile(String url)
  {
    // Don't profile requests to to results servlet
    if (url.startsWith(servletURL))
    {
      return false;
    }

    if (!restrictedURLs.isEmpty())
    {
      boolean matches = false;
      for (Pattern p : restrictedURLs)
      {
        if (p.matcher(url).find())
        {
          matches = true;
        }
      }
      if (!matches)
      {
        return false;
      }
    }

    if (restricted)
    {
      if (us.isUserLoggedIn())
      {
        if (restrictedToAdmins && !us.isUserAdmin())
        {
          return false;
        }
        if (!restrictedEmails.isEmpty() && !restrictedEmails.contains(us.getCurrentUser().getEmail()))
        {
          return false;
        }
      } else
      {
        return false;
      }
    }
    return true;
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

  /**
   * URL encode the specified string
   * 
   * @param str
   *          The string to encode.
   * @return The encoded string.
   */
  private static String urlEncode(String str)
  {
    try
    {
      return str != null ? URLEncoder.encode(str, "UTF-8") : str;
    } catch (UnsupportedEncodingException e)
    {
      throw new RuntimeException(e);
    }
  }

  /**
   * A response wrapper that:
   * <ul>
   * <li>Detects the Appstats id - used to look up Appstats data
   * programmatically
   * <li>Detects redirects and adds the current request's profiling id to a
   * request parameter used by the UI to display previous redirected requests.
   * </ul>
   */
  private static class ResponseWrapper extends HttpServletResponseWrapper
  {
    private String appstatsId;
    private String requestId;
    private String redirectRequestIds;
    private boolean didRedirect;

    public ResponseWrapper(HttpServletResponse response, String requestId, String redirectRequestIds)
    {
      super(response);
      this.requestId = requestId;
      this.redirectRequestIds = redirectRequestIds;
      didRedirect = false;
    }

    /**
     * Get the Appstats Id for this request, if any.
     * 
     * @return The Appstats Id.
     */
    public String getAppstatsId()
    {
      return appstatsId;
    }

    /**
     * Get whether this response was redirected.
     * 
     * @return Whether the response was redirected.
     */
    public boolean getDidRedirect()
    {
      return didRedirect;
    }

    /**
     * Adds the specified header.
     * <p>
     * If the header is the Appstats "X-TraceUrl" header, pull out the request
     * id from the URL.
     */
    @Override
    public void addHeader(String name, String value)
    {
      // Keep track of the Appstats request id.
      if (APPSTATS_HEADER.equalsIgnoreCase(name))
      {
        if (!isEmpty(value))
        {
          String[] parts = value.split("\\?")[1].split("&");
          for (String part : parts)
          {
            String[] nameValue = part.split("=");
            if (APPSTATS_ID_PARAM.equals(nameValue[0]))
            {
              appstatsId = nameValue[1];
            }
          }
        }
      }
      super.addHeader(name, value);
    }

    /**
     * Redirect to the specified location.
     * <p>
     * Adds the current profile request id to a {@code _mprid_} URL parameter.
     * If there are already other ids in that parameter, this request's id is
     * appended to the list (comma-separated).
     */
    @Override
    public void sendRedirect(String location) throws IOException
    {
      didRedirect = true;
      // Append the profile request ids to the redirect.
      if (!isEmpty(location))
      {
        location = String.format("%s%s%s=%s", location, location.indexOf("?") >= 0 ? "&" : "?", REQUEST_ID_PARAM_REDIRECT,
            urlEncode(redirectRequestIds != null ? redirectRequestIds + "," + requestId : requestId));
      }
      super.sendRedirect(location);
    }
  }
}
