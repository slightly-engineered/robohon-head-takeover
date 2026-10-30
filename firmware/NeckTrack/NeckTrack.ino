// ============================================================
//  NeckTrack  —  RoBoHoN head: camera-tracking neck follower
//  Target: ESP32-S3 (HW-747 Super Mini), Arduino core
//
//  The RoBoHoN Android head (TrackActivity) broadcasts the tracked
//  blob's centroid over Wi-Fi UDP. This board reads it and pans the
//  neck servo to keep the target centered — a standalone head unit,
//  separate from the mini-PLEN firmware.
//
//  Servo link: head bus, HardwareSerial(1) TX only, 115200 8N1,
//  idle HIGH. 30-byte 77 80 frames (logic-analyzer verified).
//    SID1 = NECK_TURN  (pan)   <- driven from camera X
//    SID2 = HEAD_NOD           (held at home; optional, see ENABLE_NOD)
//    SID3 = HEAD_TILT          (held at home)
//    SID4/5 = elbows           (not fitted on the head; home values sent
//                               so each frame matches the verified capture)
//
//  Packet from Android (ASCII, one UDP datagram):  "x,y\n"
//    x,y = centroid offset from frame center, scaled to -1000..+1000
//    (x = +1000 -> target at right edge,  y = +1000 -> bottom edge)
//    No packet for COMMAND_TIMEOUT_MS  ->  neck holds its last position.
// ============================================================

#include <WiFi.h>
#include <WiFiUdp.h>

// ---------------- Wi-Fi (same networks as your other boards) --------
// Wi-Fi credentials live in secrets.h (git-ignored). Copy secrets.example.h to secrets.h.
#include "secrets.h"
static const int   WIFI_COUNT = sizeof(WIFI_SSIDS) / sizeof(WIFI_SSIDS[0]);

static const uint16_t UDP_PORT = 8888;

// ---------------- Servo bus ----------------------------------------
#define BUS_TX_PIN        6      // UART1 TX -> head-bus data line
#define BUS_RX_PIN        7      // unused, but the UART driver needs a pin
#define BUS_BAUD          115200
#define FRAME_LEN         30
#define FLAG_MOVE         0x40
#define FLAG_RELAX        0xFF

HardwareSerial Bus(1);

// ---------------- Verified head-bus home values (config.h) ----------
#define HOME_NECK_TURN     239
#define HOME_HEAD_NOD    (-2530)
#define HOME_HEAD_TILT     2270
#define HOME_LEFT_ELBOW    1565
#define HOME_RIGHT_ELBOW (-1256)
#define HOME_TRAILER        109    // 6D 00

// ---------------- Mechanical limits (config.h) ----------------------
#define NECK_LIMIT_MIN  (-15361)
#define NECK_LIMIT_MAX   (14839)
#define NOD_LIMIT_MIN    (-2330)
#define NOD_LIMIT_MAX      (470)

// ---------------- Control tuning -----------------------------------
// Pan is a velocity controller: each update the neck moves toward
// centering the target at a rate proportional to how far off-center
// it is. Conservative for first bring-up — widen once direction and
// feel are confirmed.
#define PAN_DIR           (+1)   // flip to -1 if the head turns the WRONG way
#define PAN_SLEW          120    // max counts/update at full error (|x|=1000)
#define PAN_DEADBAND      0.08f  // |x_norm| below this = don't move (anti-jitter)
#define PAN_SOFT_RANGE    6000   // test window: home ± this (well inside limits)
static const int32_t PAN_MIN = HOME_NECK_TURN - PAN_SOFT_RANGE;
static const int32_t PAN_MAX = HOME_NECK_TURN + PAN_SOFT_RANGE;

// Vertical (nod) — OFF by default. HEAD_NOD has a tight range and its
// home sits just outside its own limits, so leave this until pan works.
#define ENABLE_NOD        0
#define NOD_DIR           (+1)
#define NOD_SLEW          40
#define NOD_DEADBAND      0.10f
static const int32_t NOD_CENTER = (NOD_LIMIT_MIN + NOD_LIMIT_MAX) / 2;  // -930

#define UPDATE_MS          20     // 50 Hz frame rate to the bus
#define COMMAND_TIMEOUT_MS 800    // no packets -> hold

// ---------------- State --------------------------------------------
WiFiUDP udp;
static int32_t panPos = HOME_NECK_TURN;
static int32_t nodPos = HOME_HEAD_NOD;
static float   xNorm = 0, yNorm = 0;     // latest target offset, -1..1
static bool    haveTarget = false;
static uint32_t lastPacketMs = 0;
static uint32_t lastUpdateMs = 0;
static uint32_t lastDbgMs = 0;
static uint8_t  rxbuf[64];

// ---------------- Frame helpers (ported from webCamMotionRobohon) ---
static uint8_t frameChecksum(const uint8_t* f) {
    uint32_t sum = 0;
    for (int i = 6; i < 29; ++i) sum += f[i];
    return (uint8_t)((256 - (sum & 0xFF)) & 0xFF);
}

static void putSlot(uint8_t* b, int idx, int16_t pos, uint8_t flag, uint8_t sid) {
    b[idx + 0] = (uint8_t)(pos & 0xFF);
    b[idx + 1] = (uint8_t)((pos >> 8) & 0xFF);
    b[idx + 2] = flag;
    b[idx + 3] = sid;
}

static void sendMoveFrame(int16_t neck, int16_t nod, int16_t tilt) {
    uint8_t b[FRAME_LEN];
    b[0] = 0x77; b[1] = 0x80; b[2] = 0x00; b[3] = 0x03; b[4] = 0x06; b[5] = 0x00;
    putSlot(b, 6,  neck,            FLAG_MOVE, 1);   // NECK_TURN
    putSlot(b, 10, nod,             FLAG_MOVE, 2);   // HEAD_NOD
    putSlot(b, 14, tilt,            FLAG_MOVE, 3);   // HEAD_TILT
    putSlot(b, 18, HOME_LEFT_ELBOW,  FLAG_MOVE, 4);  // not fitted -> home
    putSlot(b, 22, HOME_RIGHT_ELBOW, FLAG_MOVE, 5);
    b[26] = (uint8_t)(HOME_TRAILER & 0xFF);
    b[27] = (uint8_t)((HOME_TRAILER >> 8) & 0xFF);
    b[28] = FLAG_MOVE;
    b[29] = frameChecksum(b);
    Bus.write(b, FRAME_LEN);
    Bus.flush();
}

static void sendHome() {
    for (int i = 0; i < 10; ++i) {
        sendMoveFrame(HOME_NECK_TURN, HOME_HEAD_NOD, HOME_HEAD_TILT);
        delay(30);
    }
}

static int32_t clampL(int32_t v, int32_t lo, int32_t hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// ---------------- Wi-Fi --------------------------------------------
static void connectWiFi() {
    WiFi.mode(WIFI_STA);
    WiFi.setTxPower(WIFI_POWER_8_5dBm);   // ESP32-S3 2.4 GHz stability fix
    for (int n = 0; n < WIFI_COUNT; ++n) {
        Serial.printf("WiFi: trying %s ...\n", WIFI_SSIDS[n]);
        WiFi.begin(WIFI_SSIDS[n], WIFI_PASSWORDS[n]);
        uint32_t t0 = millis();
        while (WiFi.status() != WL_CONNECTED && millis() - t0 < 8000) {
            delay(250); Serial.print('.');
        }
        Serial.println();
        if (WiFi.status() == WL_CONNECTED) {
            Serial.printf("WiFi connected: %s  IP %s\n",
                          WIFI_SSIDS[n], WiFi.localIP().toString().c_str());
            return;
        }
    }
    Serial.println("WiFi: no network — will keep retrying in loop()");
}

// ---------------- Setup / loop -------------------------------------
void setup() {
    Serial.begin(115200);
    delay(300);
    Serial.println("\nNeckTrack starting");

    Bus.begin(BUS_BAUD, SERIAL_8N1, BUS_RX_PIN, BUS_TX_PIN);
    pinMode(BUS_TX_PIN, OUTPUT);          // idle HIGH between frames
    sendHome();                           // park neck at home
    Serial.println("neck homed");

    connectWiFi();
    udp.begin(UDP_PORT);
    Serial.printf("UDP listening on :%u\n", UDP_PORT);
}

void loop() {
    uint32_t now = millis();

    // --- read all pending UDP packets, keep the latest ---
    int sz;
    while ((sz = udp.parsePacket()) > 0) {
        int n = udp.read(rxbuf, sizeof(rxbuf) - 1);
        if (n <= 0) continue;
        rxbuf[n] = 0;
        int xi, yi;
        if (sscanf((char*)rxbuf, "%d,%d", &xi, &yi) == 2) {
            xNorm = clampL(xi, -1000, 1000) / 1000.0f;
            yNorm = clampL(yi, -1000, 1000) / 1000.0f;
            haveTarget = true;
            lastPacketMs = now;
        }
    }

    // --- Wi-Fi watchdog ---
    if (WiFi.status() != WL_CONNECTED) connectWiFi();

    // --- 50 Hz servo update ---
    if (now - lastUpdateMs >= UPDATE_MS) {
        lastUpdateMs = now;

        bool live = haveTarget && (now - lastPacketMs < COMMAND_TIMEOUT_MS);

        if (live) {
            // PAN: velocity toward centering
            if (fabsf(xNorm) > PAN_DEADBAND) {
                panPos += (int32_t)(PAN_DIR * PAN_SLEW * xNorm);
                panPos = clampL(panPos, PAN_MIN, PAN_MAX);
            }
#if ENABLE_NOD
            if (fabsf(yNorm) > NOD_DEADBAND) {
                nodPos += (int32_t)(NOD_DIR * NOD_SLEW * yNorm);
                nodPos = clampL(nodPos, NOD_LIMIT_MIN, NOD_LIMIT_MAX);
            }
#endif
        }
        // If not live, we simply re-send the last pan/nod -> servo holds.

#if ENABLE_NOD
        sendMoveFrame((int16_t)panPos, (int16_t)nodPos, HOME_HEAD_TILT);
#else
        sendMoveFrame((int16_t)panPos, HOME_HEAD_NOD, HOME_HEAD_TILT);
#endif
    }

    // --- 2 Hz debug ---
    if (now - lastDbgMs >= 500) {
        lastDbgMs = now;
        bool live = haveTarget && (now - lastPacketMs < COMMAND_TIMEOUT_MS);
        Serial.printf("x=%.2f y=%.2f pan=%ld %s\n",
                      xNorm, yNorm, (long)panPos, live ? "LIVE" : "hold");
    }
}
