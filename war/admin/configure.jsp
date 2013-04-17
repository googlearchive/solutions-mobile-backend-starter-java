<%@page import="com.google.cloud.backend.config.ConfigurationServlet"%>
<html>
<head>
<link rel='stylesheet' type='text/css' href='style.css' />
<script src="//ajax.googleapis.com/ajax/libs/jquery/1.9.0/jquery.min.js"></script>
<%
  String tokenForConfigRead=ConfigurationServlet.getToken("read");
  String tokenForConfigSave=ConfigurationServlet.getToken("save");
  String tokenForPush=ConfigurationServlet.getToken("pushmsg");
  String tokenForSubscriptions=ConfigurationServlet.getToken("clearsubs");
%>
<script type="text/javascript">
var onLoad = function() {
  $.ajaxSetup({
    type: "POST",
    error: function(xhr, status, err) {
      if (xhr.status == 401) {
        $('#butter401').show();
        setTimeout('$("#butter401").hide()', 4000);
      }
    },
  });

  var data = {};
  data.op = 'read';
  data.token = "<%=tokenForConfigRead%>"

  $.ajax({
     "url" : "cconf",
     "data": data,
     "dataType" : "json",
     "error" : function(a, b, c) { alert('error' + b + c); },
     }).done( function(data) {
       $("#" + data.authMode).attr("checked", "checked");
       $("#" + data.push).attr("checked", "checked");
       $('input[name=androidClientId]').val(data.androidClientId);
       $('input[name=iOsClientId]').val(data.iOsClientId);
       $('input[name=audience]').val(data.audience);
       $('input[name=gCMKey]').val(data.gCMKey);
       $('input:radio[name=pushEnabled][value="'+data.pushEnabled+'"]').attr("checked", "checked");
       toggle('api_key_div', 'CLIENT_ID', 'security');
       toggle('push_key_div', 'true', 'pushEnabled');
     });
};

var onSave = function() {
  var data = {};
  data.authMode = $('input:radio[name=security]:checked').val();
  data.androidClientId = $('input:text[name=androidClientId]').val();
  data.iOsClientId = $('input:text[name=iOsClientId]').val();
  data.audience = $('input:text[name=audience]').val();
  data.pushEnabled = $('input:radio[name=pushEnabled]:checked').val();;
  data.gCMKey = $('input:text[name=gCMKey]').val();
  data.op = 'save';
  data.token = "<%=tokenForConfigSave%>"

  console.log(data);

  $.ajax({
    "url": "cconf",
    "data": data,
    "dataType" : "json",
    }).done( function (data) {
      $('#butter').show();
      setTimeout('$("#butter").hide()', 4000);
    });
};

var onSendPushMessage = function() {
  var data = {};
  data.topicId = $('input:text[name=pushMsgTopicId]').val();
  data.properties = $('input:text[name=pushMsgProperties]').val();
  data.op = 'pushmsg';
  data.token = "<%=tokenForPush%>"

  console.log(data);

  $.ajax({
    "url": "cconf",
    "data": data,
    "dataType" : "json",
    }).done( function (data) {
      $('#butter').show();
      setTimeout('$("#butter").hide()', 4000);
    });
};

var onClearSubs = function() {
  var data = {};
  data.op = 'clearsubs';
  data.token = "<%=tokenForSubscriptions%>"

  console.log(data);

  $.ajax({
    "url": "cconf",
    "data": data,
    "dataType" : "json",
    }).done( function (data) {
      $('#butter').show();
      setTimeout('$("#butter").hide()', 4000);
    });
};

var toggle = function(id, value, name) {
  if ($('input:radio[name=' + name + ']:checked').val() == value) {
    $('#' + id).show();
  } else {
    $('#' + id).hide();
  }
};

</script>
<title>Mobile Backend Settings</title>
</head>

<body onload="onLoad();">
  <div class="wrap">
    <div class="header">
        <h3>Mobile Backend settings</h3>
        <div class="subtext">
          This page lets you configure the authentication and other options for your
          mobile backend. If you don't have a client application yet, download the
          <a href="https://developers.google.com/cloud/samples/repository/mbs/android">Android</a>
          sample client application.
        </div>
        <div class="butter-bar-reserve">
          <div class='butter' id='butter'>Done</div>
          <div class='butterRed' id='butter401'>Reload the page and try again</div>
        </div>
    </div>
    <div class="headerspacer"></div>
    <form id="form">
      <table>
        <!-- SECTION: Authentication / Authorization -->
        <tr>
          <td class="section-label-col">
            Authentication / Authorization
          </td>
          <td class="input-col">
            <!-- Locked down -->
            <div class="radio-selection">
              <input type="radio" name="security" id="LOCKED" value="LOCKED"
                  onclick="toggle('api_key_div', 'CLIENT_ID', 'security');">
              <div class="label-area">
                <label for="LOCKED">Locked Down (Access disabled)</label>
                <div class="subtext">All requests will be rejected.</div>
              </div>
            </div>
            <!-- Open -->
            <div class="radio-selection">
              <input type="radio" name="security" id="OPEN" value="OPEN"
                  onclick="toggle('api_key_div', 'CLIENT_ID', 'security');">
              <div class="label-area">
                <label for="OPEN">Open (for development use only)</label>
                <div class="subtext">
                  All unauthenticated requests will be allowed. The backend
                  will not be taking advantage of the integrated
                  authentication to know the identity of the callers.
                  For Android sample client app you can use either emulator
                  or a physical device.
                </div>
              </div>
            </div>
            <!-- Secured by client-id -->
            <div class="radio-selection">
              <input type="radio" name="security"
                  id="CLIENT_ID" value="CLIENT_ID"
                  onclick="toggle('api_key_div', 'CLIENT_ID', 'security');">
              <div class="label-area">
                <label for="CLIENT_ID">
                  Secured by Client IDs (Recommended)
                </label>
                <div class="subtext">
                  <div id='api_key_div' style='display: none'>
                    Only authenticated calls using the registered Client IDs
                    will be allowed. For Android sample client app you need
                    to use a physical device.
                    <p>
                    <div class="client-id">
                      <label for='androidClientId'>
                        Android Client ID
                        <a href="https://developers.google.com/console/help/#installed_applications" target="_blank">
                          Learn how to obtain an Android Client ID
                        </a>
                      </label>
                      <input type="text" id="androidClientId"
                          name="androidClientId" class="text-field">
                    </div>
                    <div class="client-id">
                      <label for='audience'>
                        Web Client ID
                        <a href="https://developers.google.com/console/help/#web_applications" target="_blank">
                          Learn how to obtain a Web Client ID
                        </a>
                      </label>
                      <input type="text" id="audience" name="audience"
                          class="text-field">
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </td>
        </tr>

        <!-- SECTION: Google Cloud Messaging -->
        <tr>
          <td class="section-label-col">
             Google Cloud Messaging<br>
            (Android only)
          </td>
          <td class="input-col">
            <!-- Disabled -->
            <div class="radio-selection">
              <input type="radio" name="pushEnabled" id="disabled"
                  value="false"
                  onclick="toggle('push_key_div', 'true', 'pushEnabled');">
              <div class="label-area">
                <label for="disabled">Disabled</label>
              </div>
            </div>

            <!-- Enabled -->
            <div class="radio-selection">
              <input type="radio" name="pushEnabled" id="enabled" value="true"
                   onclick="toggle('push_key_div', 'true', 'pushEnabled');">
              <div class="label-area">
                <label for="enabled">Enabled</label>
                <div class="subtext">
                  <div id='push_key_div' style='display: none'>
                    <label for="gCMKey">
                      Google Cloud Messaging API Key
                      <a href="http://developer.android.com/google/gcm/gs.html" target="_blank">
                        Learn how to obtain an API Key
                      </a>
                    </label>
                    <input type="text" name="gCMKey" id="gCMKey"
                        class="text-field">
                  </div>
                </div>
              </div>
            </div>
          </td>
        </tr>

        <!-- SECTION: Save -->
        <tr>
          <td></td>
          <td>
            <button type="button" class="btn active" onclick="onSave()">
              Save
            </button>
          </td>
        </tr>



        <tr>
          <td colspan="2"><hr></td>
        </tr>

        <!-- Send Cloud Message -->

        <tr>
          <td class="section-label-col">
            Send Cloud Message
          </td>
          <td class="input-col">
            <div class="subtext">
              <p>
                You can send Cloud Message to your Android client app that uses
                CloudMessageActivity class.
              </p>
              <label for="pushMsgTopicId">topicId</label>
              <input type="text" name="pushMsgTopicId" id="pushMsgTopicId"
                  value="_broadcast" class="text-field">

              <label for="pushMsgProperties">
                properties (<i>key</i>=<i>value</i>,<i>key</i>=<i>value</i>...)
              </label>
              <input type="text" name="pushMsgProperties"
                  id="pushMsgProperties" value="message=hello,duration=5"
                  class="text-field">
            </div>
          <td>
        </tr>
        <tr>
          <td/>
          <td>
            <button type="button" class="btn active"
                onclick="onSendPushMessage()">
              Send
            </button>
           </td>
        </tr>

        <tr>
          <td colspan="2"><hr></div></td>
        </tr>

        <!-- Clear all subscriptions -->
        <tr>
          <td class="section-label-col">
            <div style="text-align: right">Clear all query subscriptions</div>
          </td>
          <td class="input-col">
            <button type="button" class="btn active" onclick="onClearSubs()">
              Clear All Query Subscriptions
            </button>
          </td>
        </tr>
      </table>
    </form>
  </div>
</body>
</html>