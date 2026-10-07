# call-analyzer

Bir Java uygulamasında, verilen bir **başlangıç metodundan** itibaren metot akışını **özyinelemeli (recursive)**
olarak gezen ve belirli bir arayüzü (ör. `ICar`) uygulayan sınıflara yapılan **tüm metot çağrılarını** bulan
statik analiz aracı.

- Başlangıç metodundaki her çağrının içine girer, onun içindeki çağrıların içine girer… (her metot bir kez
  incelenir; özyineleme ve döngüler sonsuz döngüye yol açmaz).
- `car.start()` gibi arayüz/sanal çağrılarda, çalışabilecek **tüm uygulamaları** bulur (Class Hierarchy
  Analysis) ve onların da içine girer.
- Lambda'ları (`c -> c.honk()`) ve metot referanslarını (`ICar::stop`) takip eder.
- `default` arayüz metotlarını, kalıtımla gelen metotları (`Bmw` → `AbstractCar#start()`), alt arayüzleri
  (`ElectricCar extends ICar`) doğru çözümler.
- Her bulgu için **çağrı zincirini** (en kısa yol) dosya/satır bilgisiyle verir.

Analiz **derlenmiş bytecode** (`.class` / `.jar`) üzerinde yapılır ([ASM](https://asm.ow2.io/) ile). Bu sayede
tip çözümleme, jenerikler, lambda'lar ve kalıtım derleyicinin ürettiği haliyle kesin olarak görülür; kaynak kodu
ayrıştırmaya ve bağımlılıkları yapılandırmaya gerek kalmaz.

## Derleme

```bash
mvn package
# -> target/call-analyzer-1.0.0-SNAPSHOT-all.jar  (bağımlılıkları içeren çalıştırılabilir jar)
```

Java 17+ gerekir.

## Kullanım

Önce analiz edilecek uygulamayı derleyin (`mvn compile` → `target/classes`), sonra:

```bash
java -jar target/call-analyzer-1.0.0-SNAPSHOT-all.jar \
  --cp /yol/uygulama/target/classes \
  --entry 'com.example.App#main' \
  --target com.example.ICar \
  --tree
```

| Seçenek | Açıklama |
|---|---|
| `--cp <yol>` | Sınıf dizini, `.jar`/`.war` ya da `dizin/*`. `:` (Windows'ta `;`) ile ayrılabilir, tekrarlanabilir. Bağımlılık jar'larını da eklerseniz onların içine de girilir (bkz. `--include`). |
| `--entry <metot>` | Başlangıç metodu: `com.x.Garage#service`, `com.x.Garage#service(ICar, int)`, `com.x.Garage#service(Lcom/x/ICar;I)V` ya da sadece `com.x.Garage` (tüm metotlar). Arayüz metodu verilirse (`com.x.ICar#start`) tüm uygulamaları başlangıç kabul edilir. Tekrarlanabilir. |
| `--target <tip>` | Uygulayıcıları aranan arayüz/sınıf. Tekrarlanabilir. |
| `--include <önek>` | Yalnızca bu paket/sınıf önekindeki kodun içine gir (ör. `com.example`). |
| `--exclude <önek>` | Bu önekteki kodun içine girme. |
| `--max-depth <n>` | En fazla n seviye derine in (başlangıç = 0). Varsayılan: sınırsız. |
| `--only-interface-methods` | Yalnızca hedef tipin kendi metotlarını raporla (`bmw.openSunroof()` gibi sınıfa özgü metotları atla). |
| `--skip-internal` | `ICar` uygulayan sınıfların kendi içinden yaptığı çağrıları raporlama. |
| `--direct-only` | Yalnızca statik tipi zaten hedef tipten olan çağrıları raporla (`Object#toString()` üzerinden “olası” eşleşmeleri atla). |
| `--include-constructors` | `new Bmw()` gibi kurucu çağrılarını da raporla. |
| `--tree` | Gezilen çağrı ağacını da yazdır. |
| `--format text\|json` | Çıktı biçimi. JSON, CI kontrolleri ya da başka araçlarla işlemek için. |
| `-o <dosya>` | Çıktıyı dosyaya yaz. |

## Örnek

`src/test/java/sample/cars` altındaki örnek uygulamada (`ICar`, `AbstractCar`, `Bmw`, `Tesla`, `TowTruck`,
`Garage`, `Mechanic`, …) `App#main`'den başlayan analiz:

```
[13] ICar#brand()  ->  Bmw, Tesla, TowTruck
    Yer     : sample.cars.Mechanic#check(sample.cars.ICar, int)  (Mechanic.java:8)  [derinlik 4, INTERFACE]
    Not     : ICar metodu
    Zincir  : App#main(String[])
              └─ (App.java:8) Dealer#sell(Bmw)
                 └─ (Dealer.java:10) Garage#service(ICar)
                    └─ (Garage.java:7) Garage#inspect(ICar)
                       └─ (Garage.java:12) Mechanic#check(ICar, int)

[14] ICar#brand()  ->  Bmw, Tesla, TowTruck
    Yer     : sample.cars.Diagnostics#report(sample.cars.ICar)  (Diagnostics.java:8)  [derinlik 5, INTERFACE]
    Zincir  : App#main(String[])
              └─ (App.java:8) Dealer#sell(Bmw)
                 └─ (Dealer.java:10) Garage#service(ICar)
                    └─ (Garage.java:8) AbstractCar#start()
                       └─ (AbstractCar.java:8) Engine#ignite()
                          └─ (Engine.java:11) Diagnostics#report(ICar)
```

`--tree` ile çağrı ağacı (★ hedef tipe yapılan çağrı, ⇒ olası uygulama, ↻ daha önce açıldı):

```
App#main(String[])
  → Dealer#sell(Bmw)  (App.java:8)
    ★ Bmw#openSunroof()  (Dealer.java:9)  {Bmw}
    → Garage#service(ICar)  (Dealer.java:10)
      → Garage#inspect(ICar)  (Garage.java:7)
        → Mechanic#check(ICar, int)  (Garage.java:12)
          ★ ICar#brand()  (Mechanic.java:8)  {Bmw, Tesla, TowTruck}
      ★ ICar#start()  (Garage.java:8)  {Bmw, Tesla, TowTruck}
        ⇒ Bmw: AbstractCar#start()
          → Engine#ignite()  (AbstractCar.java:8)
            → Diagnostics#report(ICar)  (Engine.java:11)
              ★ ICar#brand()  (Diagnostics.java:8)  {Bmw, Tesla, TowTruck}
        ⇒ Tesla#start()
          ★ Tesla#charge()  (Tesla.java:6)  {Tesla}
  → Garage#serviceAll(List)  (App.java:11)
    ★ ICar#stop()  (Garage.java:16)  [lambda]  {Bmw, Tesla, TowTruck}
    → Garage#lambda$serviceAll$0(ICar)  (Garage.java:17)  [lambda]
      ★ ICar#honk()  (Garage.java:17)  {Bmw, Tesla, TowTruck}
```

Raporun sonunda, uygulama sınıfı bazında hangi metotların kaç yerden çağrılabildiğini gösteren bir özet de vardır.

## Programatik kullanım

```java
ClassHierarchy hierarchy = new ClassHierarchy(ClassPathLoader.load(List.of(Path.of("target/classes"))).classes());
AnalysisResult result = new CallGraphAnalyzer(hierarchy, new AnalysisOptions()
        .entry("com.example.App#main")
        .target("com.example.ICar")
        .include("com.example"))
        .analyze();
for (Finding f : result.findings()) { ... }
```

## Nasıl çalışır

1. Sınıf yolundaki tüm `.class` dosyaları okunur; her metodun içindeki çağrılar (`invokestatic`,
   `invokespecial`, `invokevirtual`, `invokeinterface` ve lambda/metot referansı `invokedynamic`) satır
   numaralarıyla çıkarılır.
2. Sınıf hiyerarşisi kurulur. Sanal/arayüz çağrısı `T.m()` için `T`'nin yüklenmiş tüm somut alt sınıflarında
   çalışacak `m` uygulaması seçilir (üst sınıflar ve `default` metotlar dahil).
3. Başlangıç metodundan genişlik öncelikli (BFS) gezinti yapılır: her çağrının hedef(ler)i kuyruğa eklenir, her
   metot bir kez incelenir. Bu sayede bulgu başına en kısa çağrı zinciri elde edilir.
4. Bir çağrı, statik alıcı tipi hedef tipin alt tipiyse **ya da** dinamik bağlamayla hedef tipi uygulayan bir
   sınıfa ulaşabiliyorsa raporlanır (ikinci durum “olası” olarak işaretlenir).

## Sınırlamalar

Statik analiz olduğu için sonuçlar **ihtiyatlıdır** (fazlasını gösterebilir):

- `ICar car` üzerinden yapılan bir çağrıda, o noktada gerçekte hangi nesnenin geldiği bilinmez; yüklenmiş tüm
  `ICar` uygulamaları olası hedef sayılır ve hepsinin içine girilir.
- Reflection (`Method.invoke`), dinamik proxy'ler, `ServiceLoader`, JNI ve sınıf yolunda olmayan kod
  görünmez. Spring gibi çatıların enjekte ettiği bean'ler ise sorun değildir: çağrı arayüz üzerinden yapıldığı
  için tüm uygulamalar zaten hedef sayılır.
- JDK sınıflarının içine girilmez. Kodunuzun JDK'ya verdiği geri çağrılar (ör. `list.forEach(c -> ...)` içindeki
  lambda, `ICar::stop`) ise takip edilir.
- Spring Boot fat-jar'ının içindeki iç içe jar'lar (`BOOT-INF/lib/*.jar`) okunmaz; bunları ayrıca `--cp` ile verin.

## Testler

```bash
mvn test
```
