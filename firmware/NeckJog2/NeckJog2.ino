// ============================================================
//  NeckJog2 — limit-aware single-servo tester, head bus (bit-bang GPIO6)
//  ESP32-C3. Moves ONE head-bus SID at a time; all others held at home,
//  so bracket-sharing servos (nod/tilt) can't fight each other.
//  Every move is clamped to the per-SID mechanical limits from your sheet.
//
//  Serial Monitor @115200, line-ending "Newline". COMMANDS:
//    s <id>     select SID 1..5  (1=NECK_TURN, 2=HEAD_NOD, 3=HEAD_TILT)
//    <number>   absolute position for selected SID (clamped to its limits)
//    +  / -     nudge selected SID by +/- step (default 300)
//    step <n>   set nudge size
//    w          SLOW sweep selected SID across its OWN limits and back
//    h          home all head servos (verified home values)
//    relax      release all head servos (stays relaxed until next move)
//    ?          print selected SID: pos, home, limits
//
//  Per-SID limits (from your range sheet):
//    SID1 NECK_TURN  -15361 .. 14839   home 239
//    SID2 HEAD_NOD    -2330 ..   470   home -2530
//    SID3 HEAD_TILT   -2130 ..  7670   home 2270
//    SID4 L_ELBOW     -1835 .. 15965   home 1565   (not fitted on head)
//    SID5 R_ELBOW    -16256 ..  1744   home -1256  (not fitted on head)
// ============================================================

#include "esp_rom_sys.h"
#define BUS_TX_PIN 6
#define FRAME_LEN  30
#define FLAG_MOVE  0x40
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
static void putSlot(uint8_t* b,int i,int16_t p,uint8_t sid){ b[i]=p&0xFF; b[i+1]=(p>>8)&0xFF; b[i+2]=FLAG_MOVE; b[i+3]=sid; }

int16_t home5[5] = { 239, -2530, 2270, 1565, -1256 };
int32_t limMin[5] = { -15361, -2330, -2130, -1835, -16256 };
int32_t limMax[5] = {  14839,   470,  7670, 15965,   1744 };
#define HOME_TRAILER 109

int32_t pos5[5];
int target = 1;
int nudge = 300;
bool relaxed = false;

static int32_t clampL(int32_t v,int32_t lo,int32_t hi){ return v<lo?lo:(v>hi?hi:v); }

void sendHold(){
  uint8_t b[FRAME_LEN];
  b[0]=0x77;b[1]=0x80;b[2]=0x00;b[3]=0x03;b[4]=0x06;b[5]=0x00;
  for(int sid=1;sid<=5;sid++) putSlot(b,6+(sid-1)*4,(int16_t)pos5[sid-1],sid);
  b[26]=HOME_TRAILER&0xFF; b[27]=(HOME_TRAILER>>8)&0xFF; b[28]=FLAG_MOVE;
  b[29]=cks(b); bbSend(BUS_TX_PIN,b,FRAME_LEN);
}
void sendRelax(){
  uint8_t b[FRAME_LEN];
  b[0]=0x77;b[1]=0x80;b[2]=0x00;b[3]=0x03;b[4]=0x06;b[5]=0x00;
  int i=6; for(uint8_t sid=1;sid<=5;sid++){ b[i++]=0x00;b[i++]=0x00;b[i++]=0xFF;b[i++]=sid; }
  b[26]=0x00;b[27]=0x00;b[28]=0xFF; b[29]=cks(b); bbSend(BUS_TX_PIN,b,FRAME_LEN);
}
void sendN(int n){ for(int i=0;i<n;i++){ sendHold(); delay(20);} }

void homeAll(){ relaxed=false; for(int i=0;i<5;i++) pos5[i]=home5[i]; sendN(8); Serial.println("homed"); }
void showState(){ Serial.printf("SID%d pos=%ld home=%d  limits[%ld..%ld]\n", target,(long)pos5[target-1],home5[target-1],(long)limMin[target-1],(long)limMax[target-1]); }

void setup(){
  Serial.begin(115200); delay(300);
  pinMode(BUS_TX_PIN,OUTPUT); digitalWrite(BUS_TX_PIN,HIGH);
  Serial.println("\nNeckJog2 ready.  s <id> | <number> | + | - | step <n> | w | h | relax | ?");
  homeAll(); showState();
}

void moveTo(int32_t v){
  relaxed=false;
  pos5[target-1]=clampL(v, limMin[target-1], limMax[target-1]);
  sendN(5); showState();
}

void loop(){
  static uint32_t t=0;
  if(millis()-t>400){ t=millis(); if(relaxed) sendRelax(); else sendHold(); }   // keep-alive

  if(!Serial.available()) return;
  String line=Serial.readStringUntil('\n'); line.trim();
  if(line.length()==0) return;

  if(line=="h"){ homeAll(); return; }
  if(line=="?"){ showState(); return; }
  if(line=="relax"){ relaxed=true; for(int k=0;k<10;k++){ sendRelax(); delay(30);} Serial.println("relaxed (stays free)"); return; }
  if(line=="+"){ moveTo(pos5[target-1]+nudge); return; }
  if(line=="-"){ moveTo(pos5[target-1]-nudge); return; }
  if(line.startsWith("step ")){ nudge=line.substring(5).toInt(); Serial.printf("nudge=%d\n",nudge); return; }
  if(line.startsWith("s ")){ int id=line.substring(2).toInt(); if(id>=1&&id<=5){ target=id; showState(); } else Serial.println("id 1..5"); return; }
  if(line=="w"){
    relaxed=false;
    int32_t lo=limMin[target-1], hi=limMax[target-1];
    Serial.printf("sweep SID%d %ld..%ld (slow)\n",target,(long)lo,(long)hi);
    int32_t stepc=(hi-lo)/80; if(stepc<50) stepc=50;
    for(int32_t p=lo;p<=hi;p+=stepc){ pos5[target-1]=p; sendN(1); delay(25); }
    for(int32_t p=hi;p>=lo;p-=stepc){ pos5[target-1]=p; sendN(1); delay(25); }
    pos5[target-1]=home5[target-1]; sendN(4); Serial.println("sweep done, SID homed"); return;
  }
  // absolute number
  int v=line.toInt();
  if(v!=0 || line=="0"){ moveTo(v); } else Serial.println("?");
}
