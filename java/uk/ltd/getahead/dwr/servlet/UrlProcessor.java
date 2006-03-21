/*
 * Copyright 2005 Joe Walker
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
package uk.ltd.getahead.dwr.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import uk.ltd.getahead.dwr.Calls;
import uk.ltd.getahead.dwr.Constants;
import uk.ltd.getahead.dwr.DebugPageGenerator;
import uk.ltd.getahead.dwr.Remoter;
import uk.ltd.getahead.dwr.Replies;
import uk.ltd.getahead.dwr.dwrp.DwrpConstants;
import uk.ltd.getahead.dwr.dwrp.DwrpHtmlJsMarshaller;
import uk.ltd.getahead.dwr.dwrp.DwrpPlainJsMarshaller;
import uk.ltd.getahead.dwr.util.IdGenerator;
import uk.ltd.getahead.dwr.util.JavascriptUtil;
import uk.ltd.getahead.dwr.util.LocalUtil;
import uk.ltd.getahead.dwr.util.Logger;
import uk.ltd.getahead.dwr.util.MimeConstants;

/**
 * This is the main servlet that handles all the requests to DWR.
 * <p>It is on the large side because it can't use technologies like JSPs etc
 * since it all needs to be deployed in a single jar file, and while it might be
 * possible to integrate Velocity or similar I think simplicity is more
 * important, and there are only 2 real pages both script heavy in this servlet
 * anyway.</p>
 * <p>There are 5 things to do, in the order that you come across them:</p>
 * <ul>
 * <li>The index test page that points at the classes</li>
 * <li>The class test page that lets you execute methods</li>
 * <li>The interface javascript that uses the engine to send requests</li>
 * <li>The engine javascript to form the iframe request and process replies</li>
 * <li>The exec 'page' that executes the method and returns data to the iframe</li>
 * </ul>
 * @author Joe Walker [joe at getahead dot ltd dot uk]
 */
public class UrlProcessor
{
    /**
     * Handle servlet requests aimed at DWR
     * @param request The servlet request
     * @param response The servlet response
     * @throws IOException If there are IO issues
     */
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        try
        {
            String pathInfo = request.getPathInfo();
            String servletPath = request.getServletPath();
            String contextPath = request.getContextPath();

            if (pathInfo == null)
            {
                pathInfo = request.getServletPath();
                servletPath = DwrpConstants.PATH_ROOT;
                log.debug("Default servlet suspected. pathInfo=" + pathInfo + "; contextPath=" + contextPath + "; servletPath=" + servletPath); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }

            String root = contextPath + servletPath;

            // NOTE: I'm not totally happy with the if statment, there doesn't
            // appear to be logic to it, just hack-till-its-not-broken which feels
            // like a good way to create latent bugs
            if (pathInfo.length() == 0 ||
                pathInfo.equals(DwrpConstants.PATH_ROOT) ||
                pathInfo.equals(contextPath))
            {
                response.sendRedirect(contextPath + servletPath + DwrpConstants.FILE_INDEX);
            }
            else if (pathInfo.startsWith(DwrpConstants.FILE_INDEX))
            {
                String page = debugPageGenerator.generateIndexPage(root);

                response.setContentType(MimeConstants.MIME_PLAIN);
                PrintWriter out = response.getWriter();
                out.print(page);
                response.flushBuffer();
            }
            else if (pathInfo.startsWith(DwrpConstants.PATH_TEST))
            {
                String scriptName = pathInfo;
                scriptName = LocalUtil.replace(scriptName, DwrpConstants.PATH_TEST, ""); //$NON-NLS-1$
                scriptName = LocalUtil.replace(scriptName, DwrpConstants.PATH_ROOT, ""); //$NON-NLS-1$

                String page = debugPageGenerator.generateTestPage(root, scriptName);

                response.setContentType(MimeConstants.MIME_PLAIN);
                PrintWriter out = response.getWriter();
                out.print(page);
                response.flushBuffer();
            }
            else if (pathInfo.startsWith(DwrpConstants.PATH_INTERFACE))
            {
                String scriptName = pathInfo;
                scriptName = LocalUtil.replace(scriptName, DwrpConstants.PATH_INTERFACE, ""); //$NON-NLS-1$
                scriptName = LocalUtil.replace(scriptName, DwrpConstants.EXTENSION_JS, ""); //$NON-NLS-1$
                String path = contextPath + servletPath;

                String script = remoter.generateInterfaceScript(scriptName, path);

                // Officially we should use MimeConstants.MIME_JS, but if we cheat and
                // use MimeConstants.MIME_PLAIN then it will be easier to read in a
                // browser window, and will still work just fine.
                response.setContentType(MimeConstants.MIME_PLAIN);
                PrintWriter out = response.getWriter();
                out.print(script);
                response.flushBuffer();
            }
            else if (pathInfo.startsWith(DwrpConstants.PATH_PLAINJS))
            {
                Calls calls = plainJsMarshaller.marshallInbound(request);
                Replies replies = remoter.execute(calls);
                plainJsMarshaller.marshallOutbound(replies, response);
            }
            else if (pathInfo.startsWith(DwrpConstants.PATH_HTMLJS))
            {
                Calls calls = htmlJsMarshaller.marshallInbound(request);
                Replies replies = remoter.execute(calls);
                htmlJsMarshaller.marshallOutbound(replies, response);
            }
            else if (pathInfo.equalsIgnoreCase(DwrpConstants.FILE_ENGINE))
            {
                doFile(request, response, DwrpConstants.FILE_ENGINE, MimeConstants.MIME_JS, true);
            }
            else if (pathInfo.equalsIgnoreCase(DwrpConstants.FILE_UTIL))
            {
                doFile(request, response, DwrpConstants.FILE_UTIL, MimeConstants.MIME_JS, false);
            }
            else
            {
                log.warn("Page not found (" + pathInfo + "). In debug/test mode try viewing /[WEB-APP]/dwr/"); //$NON-NLS-1$ //$NON-NLS-2$
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
        }
        catch (Exception ex)
        {
            // This only catches exceptions parsing the request. All execution
            // exceptions are returned inside the Call POJOs.
            if (log.isDebugEnabled())
            {
                log.warn("Error: " + ex); //$NON-NLS-1$
                log.debug("- User Agent: " + request.getHeader(HttpConstants.HEADER_USER_AGENT)); //$NON-NLS-1$
                log.debug("- Remote IP:  " + request.getRemoteAddr()); //$NON-NLS-1$
                log.debug("- Request URL:" + request.getRequestURL()); //$NON-NLS-1$
                log.debug("- Query:      " + request.getQueryString()); //$NON-NLS-1$
                log.debug("- Method:     " + request.getMethod()); //$NON-NLS-1$
                log.debug("- Body: {"); //$NON-NLS-1$

                ex.printStackTrace();
            }

            response.setContentType(MimeConstants.MIME_HTML);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            PrintWriter out = response.getWriter();
            out.println("//<script type='text/javascript'>"); //$NON-NLS-1$
            out.println("alert('Error. This may be due to an unsupported browser.\\nSee the mailing lists at http://www.getahead.ltd.uk/dwr/ for more information.');"); //$NON-NLS-1$
            out.println("//</script>"); //$NON-NLS-1$
            out.flush();
        }
    }

    /**
     * Basically a file servlet component that does some <b>very limitted</b>
     * EL type processing on the file. See the source for the cheat.
     * @param request The request from the browser
     * @param response The response channel
     * @param path The path to search for, process and output
     * @param mimeType The mime type to use for this output file
     * @param dynamic Should the script be recalculated each time?
     * @throws IOException If writing to the output fails
     */
    protected void doFile(HttpServletRequest request, HttpServletResponse response, String path, String mimeType, boolean dynamic) throws IOException
    {
        if (!dynamic && isUpToDate(request, path))
        {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        String output;

        synchronized (scriptCache)
        {
            output = (String) scriptCache.get(path);
            if (output == null)
            {
                StringBuffer buffer = new StringBuffer();

                String resource = Constants.PACKAGE + path;
                InputStream raw = getClass().getResourceAsStream(resource);
                if (raw == null)
                {
                    throw new IOException("Failed to find resource: " + resource); //$NON-NLS-1$
                }

                BufferedReader in = new BufferedReader(new InputStreamReader(raw));
                while (true)
                {
                    String line = in.readLine();
                    if (line == null)
                    {
                        break;
                    }

                    if (dynamic)
                    {
                        if (line.indexOf(PARAM_HTTP_SESSIONID) != -1)
                        {
                            line = LocalUtil.replace(line, PARAM_HTTP_SESSIONID, request.getSession(true).getId());
                        }

                        if (line.indexOf(PARAM_SCRIPT_SESSIONID) != -1)
                        {
                            line = LocalUtil.replace(line, PARAM_SCRIPT_SESSIONID, generator.generateId(pageIdLength));
                        }
                    }

                    buffer.append(line);
                    buffer.append('\n');
                }

                output = buffer.toString();

                if (mimeType.equals(MimeConstants.MIME_JS) && scriptCompressed)
                {
                    output = JavascriptUtil.compress(output, compressionLevel);
                }

                if (!dynamic)
                {
                    scriptCache.put(path, output);
                }
            }
        }

        response.setContentType(mimeType);
        response.setDateHeader(HttpConstants.HEADER_LAST_MODIFIED, servletContainerStartTime);
        response.setHeader(HttpConstants.HEADER_ETAG, etag);

        PrintWriter out = response.getWriter();
        out.println(output);
        out.flush();
    }

    /**
     * Do we need to send the conent for this file
     * @param req The HTTP request
     * @param path The file path (for debug purposes)
     * @return true iff the ETags and If-Modified-Since headers say we have not changed
     */
    private boolean isUpToDate(HttpServletRequest req, String path)
    {
        if (ignoreLastModified)
        {
            return false;
        }

        long modifiedSince = req.getDateHeader(HttpConstants.HEADER_IF_MODIFIED);
        if (modifiedSince != -1)
        {
            // Browsers are only accurate to the second
            modifiedSince -= modifiedSince % 1000;
        }
        String givenEtag = req.getHeader(HttpConstants.HEADER_IF_NONE);

        // Deal with missing etags
        if (givenEtag == null)
        {
            // There is no ETag, just go with If-Modified-Since
            if (modifiedSince > servletContainerStartTime)
            {
                if (log.isDebugEnabled())
                {
                    log.debug("Sending 304 for " + path + " If-Modified-Since=" + modifiedSince + ", Last-Modified=" + servletContainerStartTime); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return true;
            }

            // There are no modified setttings, carry on
            return false;
        }

        // Deal with missing If-Modified-Since
        if (modifiedSince == -1)
        {
            if (!etag.equals(givenEtag))
            {
                // There is an ETag, but no If-Modified-Since
                if (log.isDebugEnabled())
                {
                    log.debug("Sending 304 for " + path + " Old ETag=" + givenEtag + ", New ETag=" + etag); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                return true;
            }

            // There are no modified setttings, carry on
            return false;
        }

        // Do both values indicate that we are in-date?
        if (etag.equals(givenEtag) && modifiedSince <= servletContainerStartTime)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Sending 304 for " + path); //$NON-NLS-1$
            }
            return true;
        }

        return false;
    }

    /**
     * @param ignoreLastModified The ignoreLastModified to set.
     */
    public void setIgnoreLastModified(boolean ignoreLastModified)
    {
        this.ignoreLastModified = ignoreLastModified;
    }

    /**
     * To what level do we compress scripts?
     * @param scriptCompressed The scriptCompressed to set.
     */
    public void setScriptCompressed(boolean scriptCompressed)
    {
        this.scriptCompressed = scriptCompressed;
    }

    /**
     * @param compressionLevel The compressionLevel to set.
     */
    public void setCompressionLevel(int compressionLevel)
    {
        this.compressionLevel = compressionLevel;
    }

    /**
     * Setter for the remoter
     * @param remoter
     */
    public void setRemoter(Remoter remoter)
    {
        this.remoter = remoter;
    }

    /**
     *
     * @param debugPageGenerator
     */
    public void setDebugPageGenerator(DebugPageGenerator debugPageGenerator)
    {
        this.debugPageGenerator = debugPageGenerator;
    }

    /**
     * Setter for the Plain Javascript Marshaller
     * @param marshaller
     */
    public void setPlainJsMarshaller(DwrpPlainJsMarshaller marshaller)
    {
        this.plainJsMarshaller = marshaller;
    }

    /**
     * Setter for the HTML Javascript Marshaller
     * @param marshaller
     */
    public void setHtmlJsMarshaller(DwrpHtmlJsMarshaller marshaller)
    {
        this.htmlJsMarshaller = marshaller;
    }

    /**
     * The time on the script files
     */
    private static final long servletContainerStartTime;

    /**
     * The etag (=time for us) on the script files
     */
    private static final String etag;

    /**
     * Initialize the container start time
     */
    static
    {
        // Browsers are only accurate to the second
        long now = System.currentTimeMillis();
        servletContainerStartTime = now - (now % 1000);

        etag = "\"" + servletContainerStartTime + '\"'; //$NON-NLS-1$
    }

    /**
     * The page id length
     */
    private int pageIdLength = 16;

    /**
     * The method by which we get new page ids
     */
    private IdGenerator generator = new IdGenerator();

    /**
     * Do we ignore all the Last-Modified/ETags blathering?
     */
    private boolean ignoreLastModified = false;

    /**
     * How much do we compression javascript by?
     */
    private int compressionLevel = JavascriptUtil.LEVEL_DEBUGGABLE;

    /**
     * Do we retain comments and unneeded spaces in Javascript code?
     */
    private boolean scriptCompressed = true;

    /**
     * We cache the script output for speed
     */
    private final Map scriptCache = new HashMap();

    /**
     * The bean to handle debug page requests
     */
    private DebugPageGenerator debugPageGenerator = null;

    /**
     * The 'HTML Javascript' method by which objects are marshalled
     */
    private DwrpPlainJsMarshaller plainJsMarshaller = null;

    /**
     * The 'Plain Javascript' method by which objects are marshalled
     */
    private DwrpHtmlJsMarshaller htmlJsMarshaller = null;

    /**
     * The bean to execute remote requests and generate interfaces
     */
    private Remoter remoter = null;

    /**
     * The session id parameter that goes in engine.js
     */
    private static final String PARAM_HTTP_SESSIONID = "${httpSessionId}"; //$NON-NLS-1$

    /**
     * The page id parameter that goes in engine.js
     */
    private static final String PARAM_SCRIPT_SESSIONID = "${scriptSessionId}"; //$NON-NLS-1$

    /**
     * The log stream
     */
    private static final Logger log = Logger.getLogger(UrlProcessor.class);
}
