// ServoRelax — bit-bang the RoBoHoN head bus (GPIO6) RELAX frames so the
// neck/head servos go limp. Two-stage relax per servo_bus.cpp:
//   stage1: SID1-5 free (00 00 FF), SID6 held (6D 00 40)
//   stage2: SID1-5 free,            SID6 free   (00 00 FF)
// Re-sends stage2 every 2 s so they stay relaxed. No WiFi.

#include "esp_rom_sys.h"
#define BUS_TX_PIN 6
#define FRAME_LEN  30
static const uint32_t BIT_US = 8;

static inline void IRAM_ATTR bbByte(int pin, uint8_t b){
  noInterrupts();
  digitalWrite(pin, LOW); esp_rom_delay_us(BIT_US);
  for(int i=0;i<8;i++){ digitalWrite(pin,(b>>i)&1); esp_rom_delay_us(BIT_US); }
  digitalWrite(pin, HIGH); esp_rom_delay_us(BIT_US);
  interrupts();
}
static void IRAM_ATTR bbSend(int pin,const uint8_t* d,size_t n){ for(size_t i=0;i<n;i++) bbByte(pin,d[i]); }
static uint8_t cks(const uint8_t* f){ uint32_t s=0; for(int i=6;i<29;i++) s+=f[i]; return (uint8_t)((256-(s&0xFF))&0xFF); }

static void stage1(uint8_t* b){
  b[0]=0x77;b[1]=0x80;b[2]=0x00;b[3]=0x03;b[4]=0x06;b[5]=0x00;
  int i=6; for(uint8_t sid=1;sid<=5;sid++){ b[i++]=0x00;b[i++]=0x00;b[i++]=0xFF;b[i++]=sid; }
  b[26]=0x6D;b[27]=0x00;b[28]=0x40; b[29]=cks(b);
}
static void stage2(uint8_t* b){
  b[0]=0x77;b[1]=0x80;b[2]=0x00;b[3]=0x03;b[4]=0x06;b[5]=0x00;
  int i=6; for(uint8_t sid=1;sid<=5;sid++){ b[i++]=0x00;b[i++]=0x00;b[i++]=0xFF;b[i++]=sid; }
  b[26]=0x00;b[27]=0x00;b[28]=0xFF; b[29]=cks(b);
}

void setup(){
  Serial.begin(115200); delay(300);
  pinMode(BUS_TX_PIN,OUTPUT); digitalWrite(BUS_TX_PIN,HIGH);
  uint8_t b[FRAME_LEN];
  for(int k=0;k<3;k++){ stage1(b); bbSend(BUS_TX_PIN,b,FRAME_LEN); delay(30); }
  for(int k=0;k<7;k++){ stage2(b); bbSend(BUS_TX_PIN,b,FRAME_LEN); delay(30); }
  Serial.println("servos relaxed");
}

void loop(){
  static uint32_t t=0;
  if(millis()-t>2000){ t=millis(); uint8_t b[FRAME_LEN]; stage2(b); bbSend(BUS_TX_PIN,b,FRAME_LEN); }
}
