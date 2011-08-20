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
package ca.jimr.gae.profiler.resources;

import java.io.InputStream;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper class that loads resources (and does basic string template
 * replacement) on files in the classpath.
 * <p>
 * Once loaded (and replacements done), the files will be cached indefinitely.
 */
public class MiniProfilerResourceLoader
{
  /** Map used to store the cached resources */
  private ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<String, String>();

  /**
   * Get the specified resource (if it exists) and perform the specified string
   * replacements on it.
   * 
   * @param resource
   *          The name of the resource to load.
   * @param replacements
   *          The map of string replacements to do.
   * 
   * @return The text of the resource, or {@code null} if it could not be
   *         loaded.
   */
  public String getResource(String resource, Map<String, String> replacements)
  {
    String result = cache.get(resource);
    if (result == null)
    {
      try
      {
        InputStream is = MiniProfilerResourceLoader.class.getResourceAsStream(resource);
        try
        {
          result = new Scanner(is).useDelimiter("\\A").next();
        } finally
        {
          is.close();
        }

        if (replacements != null)
        {
          for (Map.Entry<String, String> e : replacements.entrySet())
          {
            result = result.replace(e.getKey(), e.getValue());
          }
        }

        cache.putIfAbsent(resource, result);
      } catch (Exception e)
      {
        result = null;
      }
    }
    return result;
  }
}
