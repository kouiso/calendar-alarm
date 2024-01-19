import * as admin from "firebase-admin";
import * as functions from "firebase-functions";
import { calendar_v3, google } from "googleapis";
admin.initializeApp();

const oauth2Client = new google.auth.OAuth2(
  functions.config().google.client_id,
  functions.config().google.client_secret,
  functions.config().google.redirect_uri
);

type CalendarEvent = calendar_v3.Schema$Event;

function handleError(error: any, message: string) {
  console.error(message, error);
  if (error.response) {
    console.error("API response error:", error.response.data);
  }
}

async function getCalendar() {
  oauth2Client.setCredentials({
    refresh_token: functions.config().google.refresh_token,
  });
  return google.calendar({ version: "v3", auth: oauth2Client });
}

exports.syncCalendarEvents = functions.pubsub
  .schedule("every 60 minutes")
  .onRun(async (context) => {
    try {
      const calendar = await getCalendar();
      const response = await calendar.events.list({
        calendarId: "primary",
        timeMin: new Date().toISOString(),
        maxResults: 10,
        singleEvents: true,
        orderBy: "startTime",
      });

      if (response.status !== 200) {
        throw new Error(`API returned status ${response.status}`);
      }

      const events: CalendarEvent[] = response.data.items || [];
      const batch = admin.firestore().batch();
      events.forEach((event: CalendarEvent) => {
        if (event.id) {
          const eventRef = admin
            .firestore()
            .collection("calendarEvents")
            .doc(event.id);
          batch.set(eventRef, event);
        }
      });
      await batch.commit();

      console.log("Calendar events synced successfully");
    } catch (error) {
      handleError(error, "Error syncing calendar events:");
    }
  });

exports.createGoogleCalendarWatchChannel = functions.https.onRequest(
  async (req, res) => {
    if (!isValidRequest(req)) {
      res.status(403).send("Invalid request");
      return;
    }

    try {
      const calendar = await getCalendar();
      const response = await calendar.events.watch({
        calendarId: "primary",
        requestBody: {
          id: "unique-channel-id",
          type: "web_hook",
          address: "https://your-webhook-url.com/notification",
        },
      });

      if (response) {
        console.log("Channel created", response);
        res.status(200).send("Channel created successfully");
      } else {
        throw new Error("No data in response");
      }
    } catch (err) {
      handleError(err, "Error creating channel:");
      res.status(500).send("Error creating channel");
    }
  }
);

function isValidRequest(req: functions.https.Request): boolean {
  // Implement request validation logic here
  return true; // As an example, always return true
}
