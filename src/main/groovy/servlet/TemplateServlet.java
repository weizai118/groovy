/*
 * $Id$
 * 
 * Copyright 2003 (C) James Strachan and Bob Mcwhirter. All Rights Reserved.
 * 
 * Redistribution and use of this software and associated documentation
 * ("Software"), with or without modification, are permitted provided that the
 * following conditions are met:
 * 
 * 1. Redistributions of source code must retain copyright statements and
 * notices. Redistributions must also contain a copy of this document.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * 3. The name "groovy" must not be used to endorse or promote products derived
 * from this Software without prior written permission of The Codehaus. For
 * written permission, please contact info@codehaus.org.
 * 
 * 4. Products derived from this Software may not be called "groovy" nor may
 * "groovy" appear in their names without prior written permission of The
 * Codehaus. "groovy" is a registered trademark of The Codehaus.
 * 
 * 5. Due credit should be given to The Codehaus - http://groovy.codehaus.org/
 * 
 * THIS SOFTWARE IS PROVIDED BY THE CODEHAUS AND CONTRIBUTORS ``AS IS'' AND ANY
 * EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE CODEHAUS OR ITS CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *  
 */
package groovy.servlet;

import groovy.lang.MetaClass;
import groovy.text.SimpleTemplateEngine;
import groovy.text.Template;
import groovy.text.TemplateEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.util.Map;
import java.util.WeakHashMap;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A generic servlet for serving (mostly HTML) templates.
 * 
 * It wraps a <code>groovy.text.TemplateEngine</code> to process HTTP
 * requests. By default, it uses the
 * <code>groovy.text.SimpleTemplateEngine</code> which interprets JSP-like (or
 * Canvas-like) templates. The init parameter <code>templateEngine</code>
 * defines the fully qualified class name of the template to use.<br>
 * 
 * <p>
 * Headless <code>helloworld.html</code> example
 * <pre><code>
 *  &lt;html&gt;
 *    &lt;body&gt;
 *      &lt;% 3.times { %&gt;
 *        Hello World!
 *      &lt;% } %&gt;
 *      &lt;br&gt;
 *      session.id = ${session.id}
 *    &lt;/body&gt;
 *  &lt;/html&gt; 
 * </code></pre>
 * </p>
 * 
 * @see TemplateServlet#setVariables(ServletBinding)
 * 
 * @author Christian Stein
 * @author Guillaume Laforge
 * @version 2.0
 */
public class TemplateServlet extends AbstractHttpServlet {

  /**
   * Simple cache entry that validates against last modified and length
   * attributes of the specified file. 
   *
   * @author Sormuras
   */
  private static class TemplateCacheEntry {

    long lastModified;
    long length;
    Template template;

    public TemplateCacheEntry(File file, Template template) {
      if (file == null) {
        throw new NullPointerException("file");
      }
      if (template == null) {
        throw new NullPointerException("template");
      }
      this.lastModified = file.lastModified();
      this.length = file.length();
      this.template = template;
    }

    /**
     * Checks the passed file attributes against those cached ones. 
     *
     * @param file
     *  Other file handle to compare to the cached values.
     * @return <code>true</code> if all measured values match, else <code>false</code>
     */
    public boolean validate(File file) {
      if (file == null) {
        throw new NullPointerException("file");
      }
      if (file.lastModified() != this.lastModified) {
        return false;
      }
      if (file.length() != this.length) {
        return false;
      }
      return true;
    }

  }

  /*
   * Enables more log statements.
   */
  private static final boolean VERBOSE = true;

  /**
   * Simple file name to template cache map.
   */
  // Java5 private final Map<String, TemplateCacheEntry> cache;
  private final Map cache;

  /**
   * Servlet (or the application) context.
   */
  private ServletContext context;

  /**
   * Underlying template engine used to evaluate template source files.
   */
  private TemplateEngine engine;

  /**
   * Flag that controls the appending of the "Generated by ..." comment.
   */
  private boolean generatedBy;

  /**
   * Create new TemplateSerlvet.
   */
  public TemplateServlet() {
    // Java 5 this.cache = new WeakHashMap<String, TemplateCacheEntry>();
    this.cache = new WeakHashMap();
    this.context = null; // assigned later by init()
    this.engine = null; // assigned later by init()
    this.generatedBy = true; // may be changed by init()
  }

  /**
   * Triggers the template creation eliminating all new line characters.
   * 
   * Its a work around
   * 
   * @see TemplateServlet#getTemplate(File)
   * @see BufferedReader#readLine()
   */
  private Template createTemplate(int bufferCapacity, FileReader fileReader)
      throws Exception {
    StringBuffer sb = new StringBuffer(bufferCapacity);
    BufferedReader reader = new BufferedReader(fileReader);
    try {
      String line = reader.readLine();
      while (line != null) {
        sb.append(line);
        //if (VERBOSE) { // prints the entire source file
        //  log(" | " + line);
        //}
        line = reader.readLine();
      }
    }
    finally {
      if (reader != null) {
        reader.close();
      }
    }
    StringReader stringReader = new StringReader(sb.toString());
    Template template = engine.createTemplate(stringReader);
    stringReader.close();
    return template;
  }

  /**
   * Gets the template created by the underlying engine parsing the request.
   * 
   * <p>
   * This method looks up a simple (weak) hash map for an existing template
   * object that matches the source file. If the source file didn't change in
   * length and its last modified stamp hasn't changed compared to a precompiled
   * template object, this template is used. Otherwise, there is no or an
   * invalid template object cache entry, a new one is created by the underlying
   * template engine. This new instance is put to the cache for consecutive
   * calls.
   * </p>
   * 
   * @return The template that will produce the response text.
   * @param file
   *            The HttpServletRequest.
   * @throws IOException 
   *            If the request specified an invalid template source file 
   */
  protected Template getTemplate(File file) throws ServletException {

    String key = file.getAbsolutePath();
    Template template = null;

    //
    // Test cache for a valid template bound to the key.
    //
    TemplateCacheEntry entry = (TemplateCacheEntry) cache.get(key);
    if (entry != null) {
      if (entry.validate(file)) { // log("Valid cache hit! :)");       
        template = entry.template;
      } // else log("Cached template needs recompiliation!");
    } // else log("Cache miss.");

    //
    // Template not cached or the source file changed - compile new template!
    //
    if (template == null) {
      if (VERBOSE) {
        log("Creating new template from file " + file + "...");
      }
      FileReader reader = null;
      try {
        reader = new FileReader(file);
        //
        // FIXME Template creation should eliminate '\n' by default?!
        //
        // template = engine.createTemplate(reader);
        //
        //    General error during parsing: 
        //    expecting anything but ''\n''; got it anyway
        //
        template = createTemplate((int) file.length(), reader);
      }
      catch (Exception e) {
        throw new ServletException("Creation of template failed: " + e, e);
      }
      finally {
        if (reader != null) {
          try {
            reader.close();
          }
          catch (IOException ignore) {
            // e.printStackTrace();
          }
        }
      }
      cache.put(key, new TemplateCacheEntry(file, template));
      if (VERBOSE) {
        log("Created and added template to cache. [key=" + key + "]");
      }
    }

    //
    // Last sanity check.
    //
    if (template == null) {
      throw new ServletException("Template is null? Should not happen here!");
    }

    return template;

  }

  /**
   * Initializes the servlet from hints the container passes.
   * <p>
   * Delegates to sub-init methods and parses the following parameters:
   * <ul>
   * <li> <tt>"generatedBy"</tt> : boolean, appends "Generated by ..." to the
   *     HTML response text generated by this servlet.
   *     </li>
   * </ul>
   * @param config
   *  Passed by the servlet container.
   * @throws ServletException
   *  if this method encountered difficulties 
   *  
   * @see TemplateServlet#initTemplateEngine(ServletConfig)
   */
  public void init(ServletConfig config) throws ServletException {
    super.init(config);
    this.context = config.getServletContext();
    if (context == null) {
      throw new ServletException("Context must not be null!");
    }
    this.engine = initTemplateEngine(config);
    if (engine == null) {
      throw new ServletException("Template engine not instantiated.");
    }
    
    // Use reflection, some containers don't load classes properly
    MetaClass.setUseReflection(true);
    
    String value = config.getInitParameter("generatedBy");
    if (value != null) {
      this.generatedBy = Boolean.valueOf(value).booleanValue();
    }
    if (VERBOSE) {
      log(getClass().getName() + " initialized on " + engine.getClass());
    }
  }

  /**
   * Creates the template engine.
   * 
   * Called by {@link TemplateServlet#init(ServletConfig)} and returns just 
   * <code>new groovy.text.SimpleTemplateEngine()</code> if the init parameter
   * <code>templateEngine</code> is not set by the container configuration.
   * 
   * @param config 
   *  Current serlvet configuration passed by the container.
   * 
   * @return The underlying template engine or <code>null</code> on error.
   *
   * @see TemplateServlet#initTemplateEngine(javax.servlet.ServletConfig)
   */
  protected TemplateEngine initTemplateEngine(ServletConfig config) {
    String name = config.getInitParameter("templateEngine");
    if (name == null) {
      return new SimpleTemplateEngine();
    }
    try {
      return (TemplateEngine) Class.forName(name).newInstance();
    }
    catch (InstantiationException e) {
      log("Could not instantiate template engine: " + name, e);
    }
    catch (IllegalAccessException e) {
      log("Could not access template engine class: " + name, e);
    }
    catch (ClassNotFoundException e) {
      log("Could not find template engine class: " + name, e);
    }
    return null;
  }

  /**
   * Services the request with a response.
   * <p>
   * First the request is parsed for the source file uri. If the specified file
   * could not be found or can not be read an error message is sent as response.
   * 
   * </p>
   * @param request
   *            The http request.
   * @param response
   *            The http response.
   * @throws IOException 
   *            if an input or output error occurs while the servlet is
   *            handling the HTTP request
   * @throws ServletException
   *            if the HTTP request cannot be handled
   */
  public void service(HttpServletRequest request,
      HttpServletResponse response) throws ServletException, IOException {

    if (VERBOSE) {
      log("Creating/getting cached template...");
    }

    //
    // Get the template source file handle.
    //
    File file = super.getScriptUriAsFile(request, context);
    if (!file.exists()) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return; // throw new IOException(file.getAbsolutePath());
    }
    if (!file.canRead()) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "Can not read!");
      return; // throw new IOException(file.getAbsolutePath());
    }

    //
    // Get the requested template.
    //
    long getMillis = System.currentTimeMillis();
    Template template = getTemplate(file);
    getMillis = System.currentTimeMillis() - getMillis;

    //
    // Create new binding for the current request.
    //
    ServletBinding binding = new ServletBinding(request, response, context);
    setVariables(binding);

    //
    // Prepare the response buffer content type _before_ getting the writer.
    //
    response.setContentType(CONTENT_TYPE_TEXT_HTML);

    //
    // Get the output stream writer from the binding.
    //
    Writer out = (Writer) binding.getVariable("out");
    if (out == null) {
      out = response.getWriter();
    }

    //
    // Evaluate the template.
    //
    if (VERBOSE) {
      log("Making template...");
    }
    // String made = template.make(binding.getVariables()).toString();
    // log(" = " + made);
    long makeMillis = System.currentTimeMillis();
    template.make(binding.getVariables()).writeTo(out);
    makeMillis = System.currentTimeMillis() - makeMillis;

    if (generatedBy) {
      /*
      out.append("\n<!-- Generated by Groovy TemplateServlet [create/get=");
      out.append(Long.toString(getMillis));
      out.append(" ms, make=");
      out.append(Long.toString(makeMillis));
      out.append(" ms] -->\n");
      */
    }

    //
    // Set status code and flush the response buffer.
    //
    response.setStatus(HttpServletResponse.SC_OK);
    response.flushBuffer();

    if (VERBOSE) {
      log("Template request responded. [create/get=" + getMillis
          + " ms, make=" + makeMillis + " ms]");
    }

  }

  /**
   * Override this method to set your variables to the Groovy binding.
   * <p>
   * All variables bound the binding are passed to the template source text, 
   * e.g. the HTML file, when the template is merged.
   * </p>
   * <p>
   * The binding provided by TemplateServlet does already include some default
   * variables. As of this writing, they are (copied from 
   * {@link groovy.servlet.ServletBinding}):
   * <ul>
   * <li><tt>"request"</tt> : HttpServletRequest </li>
   * <li><tt>"response"</tt> : HttpServletResponse </li>
   * <li><tt>"context"</tt> : ServletContext </li>
   * <li><tt>"application"</tt> : ServletContext </li>
   * <li><tt>"session"</tt> : request.getSession(true) </li>
   * </ul>
   * </p>
   * <p>
   * And via explicit hard-coded keywords:
   * <ul>
   * <li><tt>"out"</tt> : response.getWriter() </li>
   * <li><tt>"sout"</tt> : response.getOutputStream() </li>
   * <li><tt>"html"</tt> : new MarkupBuilder(response.getWriter()) </li>
   * </ul>
   * </p>
   * 
   * <p>Example binding all servlet context variables:
   * <pre><code>
   * class Mytlet extends TemplateServlet {
   * 
   *   private ServletContext context;
   *   
   *   public void init(ServletConfig config) {
   *     this.context = config.getServletContext();
   *   }
   * 
   *   protected void setVariables(ServletBinding binding) {
   *     Enumeration enumeration = context.getAttributeNames();
   *     while (enumeration.hasMoreElements()) {
   *       String name = (String) enumeration.nextElement();
   *       binding.setVariable(name, context.getAttribute(name));
   *     }
   *   }
   * 
   * }
   * <code></pre>
   * </p>
   * 
   * @param binding
   *  to get modified
   * 
   * @see TemplateServlet
   */
  protected void setVariables(ServletBinding binding) {
    // empty
  }

}
