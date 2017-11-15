package edu.cmu.andrew.nfirdaus;

/*
 * @author Nanda Firdaus
 * Last Modified: November 8, 2017
 *
 * This class is used as the web service for Digitalizer app.
 * It will receive the request from the mobile app, parse the sent param,
 * and send the request to Azure Cognitive Service API.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.util.HashMap;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

@WebServlet(name = "DigitalizerService", urlPatterns = {"/recognize/*"})
@MultipartConfig(fileSizeThreshold=1024*1024*2, // 2MB
                 maxFileSize=1024*1024*4,      // 4MB
                 maxRequestSize=1024*1024*50)   // 50MB
public class DigitalizerService extends HttpServlet {

    private static final String SUBSCRIPTION_KEY = "<Your API Key>";
    private static final String API_URL_HANDWRITING = "https://westcentralus.api.cognitive.microsoft.com/vision/v1.0/recognizeText";
    private static final String API_URL_PRINTED = "https://westcentralus.api.cognitive.microsoft.com/vision/v1.0/ocr";
    private MongoDbObject db;

    /**
     * Constructor of the class. Create mongo instance to be used for tracking.
     */
    public DigitalizerService() {
        db = new MongoDbObject("<mongo server>", 49335, "<username>", 
                "<password>", "<collection>");

    }
    
    /**
     * Web service operation for receiving post request
     * @param HttpServletRequest request
     * @return HttpServletResponse response
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // Modified from http://www.codejava.net/coding/upload-files-to-database-servlet-jsp-mysql
        // input stream of the upload file
        InputStream inputStream = null;

        // obtains the upload file part in this multipart request
        Part filePart = request.getPart("image");
        if (filePart != null) {
            String handwriting = request.getParameter("handwriting");
            
            // get tracking data
            String deviceModel = request.getParameter("deviceModel");
            String osVersion = request.getParameter("osVersion");
            Long timestamp = System.currentTimeMillis();
            String manufacturer = request.getParameter("manufacturer");
            String connectionType = request.getParameter("connectionType");
            
            boolean isHandwriting = Boolean.parseBoolean(handwriting);

            // obtains input stream of the upload file
            inputStream = filePart.getInputStream();
            
            long startApiCall = System.currentTimeMillis();
            // call cognitive service API and get the response in JSONObject
            JSONObject result = recognizeImage(inputStream, isHandwriting);

            long finishApiCall = System.currentTimeMillis();
            long apiLatency = finishApiCall - startApiCall;

            // Prepare tracking data
            HashMap<String, Object> trackingData = new HashMap<>();
            trackingData.put("deviceModel", deviceModel);
            trackingData.put("osVersion", osVersion);
            trackingData.put("timestamp", timestamp);
            trackingData.put("manufacturer", manufacturer);
            trackingData.put("connectionType", connectionType);
            trackingData.put("apiLatency", apiLatency);
            trackingData.put("isHandwriting", isHandwriting);

            // insert tracking data to mongo database
            db.insert("client.request", trackingData);

            // prepare response to the client
            response.setStatus(200);
            response.setContentType("text/json;charset=UTF-8");
            
            JSONObject responseData = new JSONObject();
            
            responseData.put("status", "success");
            responseData.put("data", parseResult(result, isHandwriting));

            // print response to the client
            PrintWriter out = response.getWriter();
            out.println(responseData);
        }
    }

    /**
     * Parse the result and get the important data
     *
     * @param response from Azure Cognitive Service
     * @param isHandWriting is the image contains handwriting text
     * @return
     */
    private String parseResult(JSONObject result, boolean isHandWriting) {
        String recognitionString = "";

        // if the image contains handwriting text, get the result based on the json format from docs
        if (isHandWriting) {
            JSONObject recognitionResult = result.getJSONObject("recognitionResult");
            JSONArray lines = recognitionResult.getJSONArray("lines");

            for (int i = 0; i < lines.length(); i++) {
                JSONObject item = lines.getJSONObject(i);

                recognitionString += item.getString("text") + "\n";
            }
        // if the image contains printed text, get the result based on the json format from docs
        } else {
            JSONArray regions = result.getJSONArray("regions");
            
            for (int i = 0; i < regions.length(); i++) {
                JSONObject region = regions.getJSONObject(i);
                JSONArray lines = region.getJSONArray("lines");
                
                for (int j = 0; j < lines.length(); j++) {
                    JSONObject line = lines.getJSONObject(j);
                    JSONArray words = line.getJSONArray("words");
                    
                    for (int k = 0; k < words.length(); k++) {
                        JSONObject word = words.getJSONObject(k);
                        recognitionString += word.getString("text");
                        recognitionString += " ";
                    }
                        recognitionString += "\n";
                }                
            }
        }
        
        return recognitionString;
    }
    
    /**
     * Send request to Cognitive Service API and return the response data.
     * Modified from: https://docs.microsoft.com/en-us/azure/cognitive-services/computer-vision/tutorials/java-tutorial
     *
     * @param imageUrl: The string URL of the image to process.
     * @return: A JSONObject describing the image, or null if a runtime error occurs.
     */
    private JSONObject recognizeImage(InputStream file, boolean isHandwriting) {
        try (CloseableHttpClient textClient = HttpClientBuilder.create().build();
             CloseableHttpClient resultClient = HttpClientBuilder.create().build())
        {
            // Create the URI to access the REST API call to read text in an image.
            String uriString = API_URL_HANDWRITING;
            
            URIBuilder uriBuilder = new URIBuilder(uriString);

            // add parameters for printed text
            if (!isHandwriting) {
                uriString = API_URL_PRINTED;
                uriBuilder = new URIBuilder(uriString);
                uriBuilder.setParameter("detectOrientation", "true");
                uriBuilder.setParameter("language", "unk");
            // add parameters for handwritten text
            } else {
                uriBuilder.setParameter("handwriting", "true");
            }

            // Prepare the URI for the REST API call.
            URI uri = uriBuilder.build();
            HttpPost request = new HttpPost(uri);

            // Request headers.
            request.setHeader("Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY);

            // Set image as the parameter
            ByteArrayEntity fileEntity = new ByteArrayEntity(IOUtils.toByteArray(file), ContentType.APPLICATION_OCTET_STREAM);
            request.setEntity(fileEntity);

            // Execute the REST API call and get the response.
            HttpResponse textResponse = textClient.execute(request);

            // Check for success.
            if (textResponse.getStatusLine().getStatusCode() != 202) {
                // An error occured. Return the JSON error message.
                HttpEntity entity = textResponse.getEntity();
                String jsonString = EntityUtils.toString(entity);
                return new JSONObject(jsonString);
            }

            String operationLocation = null;

            // The 'Operation-Location' in the response contains the URI to retrieve the recognized text.
            Header[] responseHeaders = textResponse.getAllHeaders();
            for(Header header : responseHeaders) {
                if(header.getName().equals("Operation-Location"))
                {
                    // This string is the URI where you can get the text recognition operation result.
                    operationLocation = header.getValue();
                    break;
                }
            }

            // NOTE: The response may not be immediately available. Handwriting recognition is an
            // async operation that can take a variable amount of time depending on the length
            // of the text you want to recognize. You may need to wait or retry this operation.
            //
            // This code checks once per second for ten seconds.

            JSONObject responseObj = null;
            int i = 0;
            do {
                // Wait one second.
                Thread.sleep(1000);

                // Check to see if the operation completed.
                HttpGet resultRequest = new HttpGet(operationLocation);
                resultRequest.setHeader("Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY);
                HttpResponse resultResponse = resultClient.execute(resultRequest);
                HttpEntity responseEntity = resultResponse.getEntity();
                if (responseEntity != null)
                {
                    // Get the JSON response.
                    String jsonString = EntityUtils.toString(responseEntity);
                    responseObj = new JSONObject(jsonString);
                }
            }
            while (i < 10 && responseObj != null &&
                    !responseObj.getString("status").equalsIgnoreCase("Succeeded"));

            // If the operation completed, return the JSON object.
            if (responseObj != null) {
                return responseObj;
            } else {
                // Return null for timeout error.
                System.out.println("Timeout error.");
                return null;
            }
        }
        catch (Exception e)
        {
            // Display error message.
            System.out.println(e.getMessage());
            return null;
        }
    }
}
