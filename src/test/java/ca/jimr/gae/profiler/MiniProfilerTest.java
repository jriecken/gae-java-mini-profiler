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

import java.util.List;

import org.junit.Test;

import ca.jimr.gae.profiler.MiniProfiler.Profile;
import ca.jimr.gae.profiler.MiniProfiler.Step;

public class MiniProfilerTest
{
  @Test
  public void testProfileNotStarted()
  {
    Step s = MiniProfiler.step("Test");
    s.close();

    Profile p = MiniProfiler.stop();
    assertNull("No profile should be generated", p);
  }

  @Test
  public void testProfileSingleStep()
  {
    Profile result = null;
    MiniProfiler.start();
    try
    {
      Step s = MiniProfiler.step("Step 1");
      s.close();
    } finally
    {
      result = MiniProfiler.stop();
    }

    assertNotNull("Profile should be generated");
    assertEquals("Request", result.getName());
    assertEquals(0, result.getDepth());
    List<Profile> children = result.getChildren();
    assertEquals(1, children.size());
    Profile child = children.get(0);
    assertEquals("Step 1", child.getName());
    assertEquals(1, child.getDepth());
  }

  @Test
  public void testProfileNestedSteps()
  {
    Profile result = null;
    MiniProfiler.start();
    try
    {
      Step s1 = MiniProfiler.step("Step 1");
      Step s11 = MiniProfiler.step("Step 1.1");
      s11.close();
      s1.close();
      Step s2 = MiniProfiler.step("Step 2");
      Step s21 = MiniProfiler.step("Step 2.1");
      s21.close();
      Step s22 = MiniProfiler.step("Step 2.2");
      s22.close();
      s2.close();
    } finally
    {
      result = MiniProfiler.stop();
    }

    assertNotNull("Profile should be generated");
    assertEquals("Request", result.getName());
    assertEquals(0, result.getDepth());
    List<Profile> children = result.getChildren();
    assertEquals(2, children.size());
    Profile child = children.get(0);
    assertEquals("Step 1", child.getName());
    assertEquals(1, child.getDepth());
    List<Profile> children2 = child.getChildren();
    assertEquals(1, children2.size());
    Profile child2 = children2.get(0);
    assertEquals("Step 1.1", child2.getName());
    assertEquals(2, child2.getDepth());
    child = children.get(1);
    assertEquals("Step 2", child.getName());
    assertEquals(1, child.getDepth());
    children2 = child.getChildren();
    assertEquals(2, children2.size());
    child2 = children2.get(0);
    assertEquals("Step 2.1", child2.getName());
    assertEquals(2, child2.getDepth());
    child2 = children2.get(1);
    assertEquals("Step 2.2", child2.getName());
    assertEquals(2, child2.getDepth());    
  }
}
