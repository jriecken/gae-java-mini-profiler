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

import static org.junit.Assert.*;

import java.util.*;

import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;

import org.junit.*;

import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.appengine.tools.development.testing.LocalUserServiceTestConfig;

public class MiniProfilerFilterTest
{
  private final LocalServiceTestHelper helper = new LocalServiceTestHelper(new LocalUserServiceTestConfig());

  @Before
  public void setUp()
  {
    helper.setUp();
  }

  @After
  public void tearDown()
  {
    helper.tearDown();
  }

  @Test
  public void testShouldProfileNoRestrict() throws Exception
  {
    MockFilterConfig cfg = new MockFilterConfig();
    cfg.filterName = "ProfilerFilter";

    MiniProfilerFilter filter = new MiniProfilerFilter();
    filter.init(cfg);

    helper.setEnvIsLoggedIn(false);

    assertTrue(filter.shouldProfile("/test/url"));
  }

  @Test
  public void testShouldProfileAdminRestrict() throws Exception
  {
    MockFilterConfig cfg = new MockFilterConfig();
    cfg.filterName = "ProfilerFilter";
    cfg.initParameters.put(MiniProfilerFilter.RESTRICT_TO_ADMINS_KEY, "true");

    MiniProfilerFilter filter = new MiniProfilerFilter();
    filter.init(cfg);

    helper.setEnvIsLoggedIn(false);
    assertFalse(filter.shouldProfile("/test/url"));

    helper.setEnvIsLoggedIn(true);
    helper.setEnvEmail("test@example.com");
    helper.setEnvAuthDomain("example.com");
    assertFalse(filter.shouldProfile("/test/url"));

    helper.setEnvIsAdmin(true);
    assertTrue(filter.shouldProfile("/test/url"));
  }

  @Test
  public void testShouldProfileURLRestrict() throws Exception
  {
    MockFilterConfig cfg = new MockFilterConfig();
    cfg.filterName = "ProfilerFilter";
    cfg.initParameters.put(MiniProfilerFilter.RESTRICT_TO_URLS_KEY, "^/test/url$,^/test/regex/.*$");

    MiniProfilerFilter filter = new MiniProfilerFilter();
    filter.init(cfg);

    runURLAssertions(filter);
  }

  @Test
  public void testShouldProfileEmailRestrict() throws Exception
  {
    MockFilterConfig cfg = new MockFilterConfig();
    cfg.filterName = "ProfilerFilter";
    cfg.initParameters.put(MiniProfilerFilter.RESTRICT_TO_EMAILS_KEY, "test@example.com,test2@example.com");

    MiniProfilerFilter filter = new MiniProfilerFilter();
    filter.init(cfg);

    helper.setEnvIsLoggedIn(false);
    assertFalse(filter.shouldProfile("/test/url"));

    helper.setEnvIsLoggedIn(true);
    helper.setEnvAuthDomain("example.com");
    helper.setEnvEmail("test@example.com");
    assertTrue(filter.shouldProfile("/test/url"));
    helper.setEnvEmail("test2@example.com");
    assertTrue(filter.shouldProfile("/test/url"));
    helper.setEnvEmail("bad@example.com");
    assertFalse(filter.shouldProfile("/test/url"));
  }
  
  @Test
  public void testShouldProfileKitchenSink() throws Exception
  {
    MockFilterConfig cfg = new MockFilterConfig();
    cfg.filterName = "ProfilerFilter";
    cfg.initParameters.put(MiniProfilerFilter.RESTRICT_TO_ADMINS_KEY, "true");
    cfg.initParameters.put(MiniProfilerFilter.RESTRICT_TO_URLS_KEY, "^/test/url$,^/test/regex/.*$");
    cfg.initParameters.put(MiniProfilerFilter.RESTRICT_TO_EMAILS_KEY, "admin@example.com");

    MiniProfilerFilter filter = new MiniProfilerFilter();
    filter.init(cfg);

    helper.setEnvIsLoggedIn(false);
    assertFalse(filter.shouldProfile("/test/url"));

    helper.setEnvIsLoggedIn(true);
    helper.setEnvEmail("test@example.com");
    helper.setEnvAuthDomain("example.com");
    assertFalse(filter.shouldProfile("/test/url"));

    helper.setEnvIsAdmin(true);
    assertFalse(filter.shouldProfile("/test/url"));
    helper.setEnvEmail("admin@example.com");
    assertTrue(filter.shouldProfile("/test/url"));
    
    runURLAssertions(filter);
  }

  private void runURLAssertions(MiniProfilerFilter filter)
  {
    assertFalse(filter.shouldProfile("/some/url"));
    assertTrue(filter.shouldProfile("/test/url"));
    assertFalse(filter.shouldProfile("/test/url2"));
    assertTrue(filter.shouldProfile("/test/regex/"));
    assertTrue(filter.shouldProfile("/test/regex/blah"));
    assertTrue(filter.shouldProfile("/test/regex/foo/bar/baz"));
  }
  
  private static class MockFilterConfig implements FilterConfig
  {
    public String filterName;
    public Map<String, String> initParameters = new HashMap<String, String>();

    @Override
    public String getFilterName()
    {
      return filterName;
    }

    @Override
    public String getInitParameter(String key)
    {
      return initParameters.get(key);
    }

    @Override
    public Enumeration<String> getInitParameterNames()
    {
      return Collections.enumeration(initParameters.keySet());
    }

    @Override
    public ServletContext getServletContext()
    {
      return null;
    }

  }
}
