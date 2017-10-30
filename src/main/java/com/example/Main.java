package com.example;

import com.dropbox.core.*;
import com.dropbox.core.json.JsonReader;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.UploadErrorException;
import com.dropbox.core.v2.files.WriteMode;
import com.dropbox.core.v2.users.FullAccount;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.util.Date;
import java.util.Map;

@Controller
@SpringBootApplication
public class Main {

  //private String app_url = "https://nameless-sands-54583.herokuapp.com/";
  //private String argAppInfoFile = "appInfo.app";

  private String app_url = "http://localhost:5000/";
  private String argAppInfoFile = "appInfo_secret.app";

  private String redirectUri = app_url + "connected";

  private User user = new User();



  private DbxRequestConfig requestConfig;
  private DbxAppInfo appInfo;
  private DbxWebAuth auth;
  private DbxClientV2 client;
  private FullAccount account;

  public static void main(String[] args) throws Exception {
    SpringApplication.run(Main.class, args);
  }


  @RequestMapping("/")
  String index() {

    user.access_tkn = null;

    requestConfig = new DbxRequestConfig("text-edit/0.1");

    //System.out.println("Working Directory = " + System.getProperty("user.dir"));

    try {
      appInfo = DbxAppInfo.Reader.readFromFile(argAppInfoFile);
    } catch (JsonReader.FileLoadException ex) {
      System.err.println("Error reading <app-info-file>: " + ex.getMessage());
    }

    auth = new DbxWebAuth(requestConfig, appInfo);

    return "index";
  }

  @RequestMapping(value = "/get_code", method = RequestMethod.GET)
  void get_token(HttpServletRequest request, HttpServletResponse response){

      // Select a spot in the session for DbxWebAuth to store the CSRF token.
      HttpSession session = request.getSession(true);
      String sessionKey = "dropbox-auth-csrf-token";
      DbxSessionStore csrfTokenStore = new DbxStandardSessionStore(session, sessionKey);

      // Build an auth request
      DbxWebAuth.Request authRequest = DbxWebAuth.newRequestBuilder()
              .withRedirectUri(redirectUri, csrfTokenStore)
              .build();

      // Start authorization.
      String authorizePageUrl = auth.authorize(authRequest);

      // Redirect the user to the Dropbox website so they can approve our application.
      // The Dropbox website will send them back to "http://my-server.com/dropbox-auth-finish"
      // when they're done.
      try{
        response.sendRedirect(authorizePageUrl);
      }catch (IOException e) {
          System.err.println("Error send redirect: " + e.getMessage());
      }

    return;
  }

  @RequestMapping("/connected")
  String connected(Map<String, Object> model, HttpServletRequest request, HttpServletResponse response){

      // Fetch the session to verify our CSRF token
      HttpSession session = request.getSession(true);
      String sessionKey = "dropbox-auth-csrf-token";
      DbxSessionStore csrfTokenStore = new DbxStandardSessionStore(session, sessionKey);

      //exchange token with code
      DbxAuthFinish authFinish;
      String action = "/connected (exchanging token with code)";
      try {
          authFinish = auth.finishFromRedirect(redirectUri, csrfTokenStore, request.getParameterMap());
      } catch (DbxWebAuth.BadRequestException ex) {

          exceptionHandler(action, response, "Bad request: " + ex.getMessage(), 400, "");
          model.put("status", "fail connected to Dropbox");
          return "connected";

      } catch (DbxWebAuth.BadStateException ex) {

          model.put("status", "fail connected to Dropbox");
          return "connected";

      } catch (DbxWebAuth.CsrfException ex) {

          exceptionHandler(action, response, "CSRF mismatch: " + ex.getMessage(), 403, "Forbidden.");
          model.put("status", "fail connected to Dropbox");
          return "connected";

      } catch (DbxWebAuth.NotApprovedException ex) {

          // When Dropbox asked "Do you want to allow this app to access your
          // Dropbox account?", the user clicked "No".
          exceptionHandler(action, response, "Not Approved Exception: " + ex.getMessage(), 503, "Not Approved");
          model.put("status", "Please approve this app to connect to your Dropbox");
          return "connected";

      } catch (DbxWebAuth.ProviderException ex) {

          exceptionHandler(action, response, "Auth failed: " + ex.getMessage(), 503, "Error communicating with Dropbox.");
          model.put("status", "fail connected to Dropbox");
          return "connected";

      } catch (DbxException ex) {

          exceptionHandler(action, response, "Error getting token: " + ex.getMessage(), 503, "Error communicating with Dropbox.");
          model.put("status", "fail connected to Dropbox");
          return "connected";

      }

      user.access_tkn = authFinish.getAccessToken();

      // Now use the access token to make Dropbox API calls.
      client = new DbxClientV2(requestConfig, user.access_tkn);

      // Get current account info
      try{

          account = client.users().getCurrentAccount();
          user.display_name = account.getName().getDisplayName();
          //System.out.println(account.getName().getDisplayName());

      } catch (DbxException e){
          System.err.println("get user account info fail " + e.getMessage());
      }

      model.put("status", "connected to Dropbox");
      model.put("display_name",user.display_name);

      return "connected";
  }

  @ResponseBody
  @RequestMapping(value = "/upload", method = RequestMethod.POST)
  String upload(MultipartHttpServletRequest request){

      MultipartFile file = request.getFile("file");
      String name = file.getOriginalFilename();

      System.out.println("\nupload  : " + name);

      String dropboxPath = "/" + name;
      String message = "";

      if(!name.isEmpty() && name.length() > 0){
          File localFile = new File(name);
          try {
              localFile.createNewFile();
              FileOutputStream fos = new FileOutputStream(localFile);
              fos.write(file.getBytes());
              fos.close();
          } catch (IOException e){
              System.err.println("Invalid <local-path>: create file fail" + localFile.toString());
          }

          if (!localFile.exists()) {
              System.err.println("Invalid <local-path>: file does not exist." + localFile.toString());
              message = "\" "+ localFile.toString() +" \" does not exist";
          }
          else if (!localFile.isFile()) {
              System.err.println("Invalid <local-path>: not a file." + localFile.toString());
              message = "\" "+ localFile.toString() +" \" not a file";
          }
          else  message = uploadFile(client, localFile, dropboxPath);

          localFile.delete();
      }

      return message;
    }

  private void exceptionHandler(String action, HttpServletResponse response, String msg, int code, String para){

      System.err.println("On "+ action +": " + msg);
      try{
          response.sendError(code, para);
      } catch (IOException ex){
          System.err.println("On "+ action +": IOException : " + ex.getMessage());
      }
      return;
  }

  private static String uploadFile(DbxClientV2 dbxClient, File localFile, String dropboxPath) {

      String status;

      try (InputStream in = new FileInputStream(localFile)) {

          FileMetadata metadata = dbxClient.files().uploadBuilder(dropboxPath)
                  .withMode(WriteMode.ADD)
                  .withClientModified(new Date(localFile.lastModified()))
                  .uploadAndFinish(in);

          //System.out.println(metadata.toStringMultiline());
          status = "\"" + localFile + "\" uploaded successfully";

      } catch (UploadErrorException ex) {

          System.err.println("Error uploading to Dropbox: " + ex.getMessage());
          status = "Error uploading \" " + localFile + " \"";

      } catch (DbxException ex) {

          System.err.println("Error uploading to Dropbox: " + ex.getMessage());
          status = "Error uploading \" " + localFile + " \"";

      } catch (IOException ex) {

          System.err.println("Error reading from file \"" + localFile + "\": " + ex.getMessage());
          status = "Error reading from file \" " + localFile + " \"";
      }

      return status;
  }

}
