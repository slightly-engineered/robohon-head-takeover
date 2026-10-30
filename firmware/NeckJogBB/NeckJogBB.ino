// ============================================================
//  NeckJogBB — serial servo tester, BIT-BANG TX (RoBoHoN head bus)
//  ESP32-C3. No WiFi. Bit-bang on GPIO6, ported from your verified
//  servo_bus.cpp (BIT_US=8, idle HIGH, IRAM_ATTR, esp_rom_delay_us).
//
//  Wiring: GPIO6 -> servo DATA (half-duplex, ~1k series R),
//          servo (+) -> 3.7 V, GND common with the C3.
//
//  Serial Monitor @115200, line-ending "Newline". COMMANDS:
//    h          home SID1-3
//    s <id>     select SID 1..5   (1=NECK_TURN default)
//    <number>   set selected SID to absolute position, send
//    +   / -    nudge selected SID by +/-500
//    w          sweep selected SID home-4000..home+4000..back
//    scan       pulse every SID 1..5 by +/-3000 (find which one moves)
//    ?          print state
// ============================================================

#include "esp_rom_sys.h"

#define BUS_TX_PIN   6
#define FRAME_LEN    30
#define FLAG_MOVE    0x40
static const uint32_t BIT_US = 8;     // ~125k baud, matches servo_bus.cpp

// ---- bit-bang UART TX (idle HIGH), verified timing ----
static inline void IRAM_ATTR bbByte(int pin, uint8_t b){
  noInterrupts();
  digitalWrite(pin, LOW);                 // start bit
  esp_rom_delay_us(BIT_US);
  for(int i=0;i<8;i++){ digitalWrite(pin, (b>>i)&1); esp_rom_delay_us(BIT_US); }
  digitalWrite(pin, HIGH);                // stop bit
  esp_rom_delay_us(BIT_US);
  interrupts();
}
static void IRAM_ATTR bbSend(int pin, const uint8_t* d, size_t n){
  for(size_t i=0;i<n;i++) bbByte(pin, d[i]);
}

// head-bus home values (config.h)
int16_t home5[5] = { 239, -2530, 2270, 1565, -1256 };  // SID1..5
#define HOME_TRAILER 109
int16_t pos5[5];
int target = 1;

static uint8_t checksum(const uint8_t* f){ uint32_t s=0; for(int i=6;i<29;i++) s+=f[i]; return (uint8_t)((256-(s&0xFF))&0xFF); }
static void putSlot(uint8_t* b,int i,int16_t p,uint8_t sid){ b[i]=p&0xFF; b[i+1]=(p>>8)&0xFF; b[i+2]=FLAG_MOVE; b[i+3]=sid; }

void sendFrame(){
  uint8_t b[FRAME_LEN];
  b[0]=0x77;b[1]=0x80;b[2]=0x00;b[3]=0x03;b[4]=0x06;b[5]=0x00;
  for(int sid=1; sid<=5; sid++) putSlot(b, 6+(sid-1)*4, pos5[sid-1], sid);
  b[26]=HOME_TRAILER&0xFF; b[27]=(HOME_TRAILER>>8)&0xFF; b[28]=FLAG_MOVE;
  b[29]=checksum(b);
  bbSend(BUS_TX_PIN, b, FRAME_LEN);
}
void sendN(int n){ for(int i=0;i<n;i++){ sendFrame(); delay(20);} }
void homeAll(){ for(int i=0;i<5;i++) pos5[i]=home5[i]; sendN(8); Serial.println("homed"); }
void showState(){ Serial.printf("SID%d pos=%d (home=%d)\n", target, pos5[target-1], home5[target-1]); }

void setup(){
  Serial.begin(115200); delay(300);
  pinMode(BUS_TX_PIN, OUTPUT);
  digitalWrite(BUS_TX_PIN, HIGH);         // idle HIGH
  Serial.println("\nNeckJogBB (bit-bang GPIO6) ready. h | s <id> | <number> | + | - | w | scan | ?");
  homeAll();
}

void loop(){
  static uint32_t t=0; if(millis()-t>400){ t=millis(); sendFrame(); }   // keep-alive hold
  if(!Serial.available()) return;
  String line = Serial.readStringUntil('\n'); line.trim();
  if(line.length()==0) return;

  if(line=="h"){ homeAll(); return; }
  if(line=="?"){ showState(); return; }
  if(line=="+"){ pos5[target-1]+=500; sendN(4); showState(); return; }
  if(line=="-"){ pos5[target-1]-=500; sendN(4); showState(); return; }
  if(line.startsWith("s ")){ int id=line.substring(2).toInt(); if(id>=1&&id<=5){ target=id; showState(); } else Serial.println("id 1..5"); return; }
  if(line=="w"){
    int h=home5[target-1]; Serial.println("sweep...");
    for(int p=h-4000;p<=h+4000;p+=200){ pos5[target-1]=p; sendN(1); delay(15); }
    for(int p=h+4000;p>=h-4000;p-=200){ pos5[target-1]=p; sendN(1); delay(15); }
    pos5[target-1]=h; sendN(4); Serial.println("sweep done"); return;
  }
  if(line=="scan"){
    Serial.println("scan: pulsing each SID 1..5 by +/-3000");
    for(int sid=1; sid<=5; sid++){
      Serial.printf("  SID%d\n", sid);
      for(int k=0;k<2;k++){
        pos5[sid-1]=home5[sid-1]+3000; sendN(6); delay(250);
        pos5[sid-1]=home5[sid-1]-3000; sendN(6); delay(250);
      }
      pos5[sid-1]=home5[sid-1]; sendN(4);
    }
    Serial.println("scan done"); return;
  }
  int v = line.toInt();
  if(v!=0 || line=="0"){ pos5[target-1]=v; sendN(5); showState(); }
  else Serial.println("?");
}
