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
| `--acquirer <metot>` | Ör. `com.example.CarAcquirer#acquire`. Akışta bu metoda verilen listedeki `ICar` nesnelerini bulur ve akışta kullanılan sınıflarla karşılaştırır (bkz. aşağıda). |
| `--acquire-annotation <tip>` | Ör. `com.example.AcquireCar`. Analiz edilen (giriş) metotta bu anotasyon var mı bakar, içinde verilen sınıfları listeler ve karşılaştırmaya ekler. `--acquirer` ile birlikte kullanılabilir. |
| `--fail-on-missing` | Karşılaştırmada kesin eksik sınıf bulunursa çıkış kodu `3` olur (CI'da kontrol için). |
| `--event-class`, `--event-dispatcher`, `--event-observer` | Event takibi (bkz. aşağıda). Üçü birlikte verilir. |
| `--event-handle-method <ad>` | Observer'ın event'i alan metodu (varsayılan `handle`). |
| `--config <dosya>` | Seçenekleri bir ayar dosyasından oku (bkz. aşağıda). |
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
              `- (App.java:8) Dealer#sell(Bmw)
                 `- (Dealer.java:10) Garage#service(ICar)
                    `- (Garage.java:7) Garage#inspect(ICar)
                       `- (Garage.java:12) Mechanic#check(ICar, int)

[14] ICar#brand()  ->  Bmw, Tesla, TowTruck
    Yer     : sample.cars.Diagnostics#report(sample.cars.ICar)  (Diagnostics.java:8)  [derinlik 5, INTERFACE]
    Zincir  : App#main(String[])
              `- (App.java:8) Dealer#sell(Bmw)
                 `- (Dealer.java:10) Garage#service(ICar)
                    `- (Garage.java:8) AbstractCar#start()
                       `- (AbstractCar.java:8) Engine#ignite()
                          `- (Engine.java:11) Diagnostics#report(ICar)
```

`--tree` ile çağrı ağacı (`*` hedef tipe yapılan çağrı, `=>` olası uygulama, `^` daha önce açıldı):

```
App#main(String[])
  -> Dealer#sell(Bmw)  (App.java:8)
    * Bmw#openSunroof()  (Dealer.java:9)  {Bmw}
    -> Garage#service(ICar)  (Dealer.java:10)
      -> Garage#inspect(ICar)  (Garage.java:7)
        -> Mechanic#check(ICar, int)  (Garage.java:12)
          * ICar#brand()  (Mechanic.java:8)  {Bmw, Tesla, TowTruck}
      * ICar#start()  (Garage.java:8)  {Bmw, Tesla, TowTruck}
        => Bmw: AbstractCar#start()
          -> Engine#ignite()  (AbstractCar.java:8)
            -> Diagnostics#report(ICar)  (Engine.java:11)
              * ICar#brand()  (Diagnostics.java:8)  {Bmw, Tesla, TowTruck}
        => Tesla#start()
          * Tesla#charge()  (Tesla.java:6)  {Tesla}
  -> Garage#serviceAll(List)  (App.java:11)
    * ICar#stop()  (Garage.java:16)  [lambda]  {Bmw, Tesla, TowTruck}
    -> Garage#lambda$serviceAll$0(ICar)  (Garage.java:17)  [lambda]
      * ICar#honk()  (Garage.java:17)  {Bmw, Tesla, TowTruck}
```

Raporun sonunda, uygulama sınıfı bazında hangi metotların kaç yerden çağrılabildiğini gösteren bir özet de vardır.

## Acquire listesi karşılaştırması (`--acquirer`)

Akışın başında genellikle `CarAcquirer.acquire(List<ICar>)` gibi bir çağrı varsa, araç bu listeye konan `ICar`
nesnelerini bulur ve akışta gerçekten çağrılan `ICar` sınıflarıyla karşılaştırır:

```bash
java -jar call-analyzer-1.0.0-SNAPSHOT-all.jar --cp uygulamam.jar \
  --entry 'com.example.RentalService#rent' --target com.example.ICar \
  --acquirer 'com.example.CarAcquirer#acquire'
```

```java
@Autowired private Bmw bmw;
@Autowired private Tesla tesla;
@Autowired private TowTruck towTruck;

public void rent() {
    CarAcquirer.acquire(List.of(bmw, tesla));
    bmw.openSunroof();
    tesla.charge();
    towTruck.tow(bmw);   // TowTruck listede yok!
}
```

```
=== Acquire karsilastirmasi: com.example.CarAcquirer#acquire ===
Cagri: RentalService#rent()  (RentalService.java:14)
    Bmw            <- alan RentalService.bmw : Bmw
    Tesla          <- alan RentalService.tesla : Tesla
Acquire edilenler    : Bmw, Tesla
Akista kullanilanlar : Bmw, Tesla, TowTruck

[X] EKSIK - akista kesin olarak bu sinifa cagri var ama acquire edilmemis (listede/anotasyonda yok):
  com.example.TowTruck
      TowTruck#tow(ICar)           RentalService#rent() (RentalService.java:17)

[OK] Acquire edilmis ve kullaniliyor: Bmw, Tesla
```

Sonuç kategorileri:

| Kategori | Anlamı |
|---|---|
| `[X] EKSIK` | Çağrının hedefi kesin olarak bu sınıf (ör. `Bmw` tipli alan üzerinden `bmw.x()`), ama sınıf acquire edilmemiş (listede/anotasyonda yok). |
| `? OLASI EKSIK` | Sınıf yalnızca `ICar` arayüzü üzerinden yapılan çağrılarda olası hedeflerden biri (statik analiz hangi nesnenin geleceğini bilemez) ve acquire edilmemiş. |
| `~ BELIRSIZ` | Listede sınıfı belirlenemeyen bir eleman var (ör. `ICar` tipli bir alan); bu sınıf o eleman olabilir. |
| `! ACQUIRE EDILIP AKISTA KULLANILMAYAN` | Listeye/anotasyona konmuş ama akışta hiç çağrılmayan sınıflar. |
| `[OK]` | Acquire edilmiş ve kullanılan sınıflar. |

Listenin içeriği bytecode üzerinde veri akışı analiziyle bulunur. Desteklenen kurulumlar:

- Somut tipli alanlar: `@Autowired Bmw bmw` → `acquire(List.of(bmw, tesla))`
- `new`: `acquire(List.of(new Bmw(), new Tesla()))`, `Arrays.asList(...)`, `new ArrayList<>(List.of(...))`
- `List<ICar> l = new ArrayList<>(); l.add(bmw); acquire(l)`
- Listeyi döndüren bir metot (`acquire(cars())`), liste tutan bir alan (kurucuda doldurulan `fleet`)
- Listenin parametre olarak gelmesi (`run(List.of(bmw))` → `run(List<ICar> cars) { acquire(cars); }`): akıştaki
  çağıranlara bakılır.

Karşılaştırmada şunlar sayılmaz: `acquire` metodunun kendi içindeki çağrılar, statik metotlar ve kurucular, bir
`ICar` sınıfının kendi metotlarını çağırması (`this` üzerinden; nesne zaten hesaba katılmıştır).

## Acquire anotasyonu (`--acquire-annotation`)

`CarAcquirer.acquire(...)` çağırmak yerine metodu bir anotasyonla işaretliyorsanız:

```java
@AcquireCar(cars = {Opel.class, Mercedes.class})
public void rent() { ... }
```

```bat
java -jar call-analyzer-1.0.0-SNAPSHOT-all.jar --cp uygulamam.jar --entry "com.example.RentalService#rent" --target com.example.ICar --acquire-annotation com.example.AcquireCar
```

Araç giriş metodunda anotasyonun olup olmadığını (`VAR` / `YOK`) ve içinde verilen sınıfları yazar; bu sınıflar
acquire edilmiş sayılır ve yukarıdaki karşılaştırmaya eklenir. `--acquirer` ile birlikte verilirse anotasyondaki
sınıflar ile `acquire(...)` listesindeki sınıflar birleştirilir.

```
=== Acquire karsilastirmasi: com.example.CarAcquirer#acquire, @com.example.AcquireCar ===
Anotasyon @AcquireCar: VAR  RentalService#rent()  (RentalService.java:44)
    Tesla          <- @AcquireCar ile verildi: Tesla.class
Cagri: RentalService#rent()  (RentalService.java:44)
    Bmw            <- alan RentalService.bmw : Bmw
Acquire edilenler    : Bmw, Tesla
Akista kullanilanlar : Bmw, Tesla, TowTruck

[X] EKSIK - akista kesin olarak bu sinifa cagri var ama acquire edilmemis (listede/anotasyonda yok):
  com.example.TowTruck
      TowTruck#tow(ICar)           RentalService#rent() (RentalService.java:47)
```

Notlar:

- Anotasyonun içindeki öznitelik adı önemli değildir (`cars`, `value`, ...); anotasyondaki tüm `X.class`
  değerleri okunur. Tek sınıf da (`cars = Opel.class`), dizi de (`cars = {Opel.class, Mercedes.class}`) olur.
- Anotasyon `RUNTIME` ya da varsayılan (`CLASS`) retention ile tanımlıysa bulunur. `@Retention(SOURCE)` ise
  derlenmiş koda hiç yazılmadığı için görülemez.
- Yalnızca giriş metodunun (`--entry`) kendi anotasyonuna bakılır; sınıf üzerindeki ya da akışta daha derindeki
  metotlardaki anotasyonlar dikkate alınmaz.
- Anotasyonda `ICar`'ı uygulamayan bir sınıf verilmişse yanında `(hedef tipi uygulamiyor!)` yazar.

## Event takibi (`--event-*`)

Akış sırasında bir `EventDispatcher` üzerinden event yayınlanıyor ve aynı thread'de observer'lar çalışıyorsa:

```java
public class CarRentedObserver implements IObserver<CarRentedEvent> {
    public CarRentedObserver(EventDispatcher dispatcher) {
        dispatcher.register(CarRentedEvent.NAME, this);      // "CAR_RENTED"
    }
    public void handle(CarRentedEvent event) {
        towTruck.tow(event.getCar());                         // ICar kullanimi
        dispatcher.dispatch(new CarReturnedEvent());          // inner event
    }
}
```

```bat
java -jar call-analyzer-1.0.0-SNAPSHOT-all.jar --cp uygulamam.jar --entry "com.example.RentalService#rent" --target com.example.ICar --event-class com.example.event.Event --event-dispatcher com.example.event.EventDispatcher --event-observer com.example.event.IObserver
```

Event ayarı olmadan araç `dispatcher.dispatch(event)` içindeki `observer.handle(event)` çağrısında **tüm**
`IObserver` uygulamalarına iner. Event ayarıyla:

1. Sınıf yolundaki tüm `register(...)` çağrıları taranır ve hangi event adına hangi observer'ın kayıtlı olduğu
   çıkarılır (`dispatcher.register("CAR_RENTED", this)`; ad bir sabit ya da `static final` alan olabilir; kayıt
   kurucuda ya da `init`/`@PostConstruct` gibi bir metotta yapılabilir; `unregister`/`remove...` sayılmaz).
2. Akışta bir `dispatch(event)` görüldüğünde event'in sınıfı ve adı bulunur:
   - `super("CAR_RENTED")` / `super(NAME)` ile kurucuda verilen ad,
   - `new NamedEvent("CAR_RETURNED")` gibi oluşturulduğu yerde verilen ad,
   - sabit dönen bir `getName()` override'ı,
   - event bir yardımcı metoda parametre olarak geçiyorsa (`publish(Event e)`), akıştaki çağıranları.
3. Akış yalnızca o ada kayıtlı observer'ların `handle` metoduna devam eder (generic `IObserver<T>` için derleyicinin
   ürettiği köprü metot değil, `handle(CarRentedEvent)` seçilir). Observer'ın içinde yayınlanan event'ler (inner
   event) de aynı şekilde takip edilir.

Ad bulunamazsa (ör. çalışma anında hesaplanıyorsa) `handle` parametre tipi event sınıfını kabul eden observer'lara,
event hakkında hiçbir şey bilinmiyorsa (ör. giriş metodunun parametresi) tüm observer'lara gidilir; raporda bu
"Secim" satırında belirtilir.

Çıktıda bulguların zincirinde event geçişleri görünür, ayrıca bir event bölümü eklenir:

```
[4] Tesla#charge()  ->  Tesla
    Yer     : sample.events.CarReturnedObserver#handle(sample.events.CarReturnedEvent)  (CarReturnedObserver.java:15)  [derinlik 2, VIRTUAL]
    Zincir  : EventFlowService#rent()
              `- (EventFlowService.java:11) [event CAR_RENTED] CarRentedObserver#handle(CarRentedEvent)
                 `- (CarRentedObserver.java:18) [event CAR_RETURNED] CarReturnedObserver#handle(CarReturnedEvent)

=== Event akisi ===
Kayitli observer'lar (5 kayit):
    AUDIT                    -> AuditObserver
    CAR_RENTED               -> CarRentedObserver, KafkaCarRentedObserver
    CAR_RETURNED             -> CarReturnedObserver
    PAYMENT_DONE             -> PaymentObserver

[E1] EventFlowService#rent()  (EventFlowService.java:11)  EventDispatcher#dispatch(Event)
     Event      : CarRentedEvent   ad: "CAR_RENTED"
     Secim      : event adina kayitli observer'lar
     Observer'lar: CarRentedObserver#handle(CarRentedEvent), KafkaCarRentedObserver#handle(CarRentedEvent)

[E2] CarRentedObserver#handle(CarRentedEvent)  (CarRentedObserver.java:18)  EventDispatcher#dispatch(Event)
     Event      : CarReturnedEvent   ad: "CAR_RETURNED"
     Secim      : event adina kayitli observer'lar
     Observer'lar: CarReturnedObserver#handle(CarReturnedEvent)
```

Kafka'ya event basan observer'lar da analiz edilir (o ana kadar aynı thread'de çalışırlar); Kafka'nın öbür
tarafındaki tüketiciler ayrı bir akıştır ve takip edilmez. Event sınıfları `--include` filtresi dışında kalsa bile
event adlarını bulmak için okunur.

## Ayar dosyası (`--config`)

Seçenekleri her seferinde yazmamak için bir dosyaya koyabilirsiniz (örnek: `ornek-ayar.properties`):

```properties
cp=uygulamam.jar
target=com.example.ICar
acquirer=com.example.CarAcquirer#acquire
event-class=com.example.event.Event
event-dispatcher=com.example.event.EventDispatcher
event-observer=com.example.event.IObserver
tree=true
```

```bat
java -jar call-analyzer-1.0.0-SNAPSHOT-all.jar --config ayar.properties --entry "com.example.RentalService#rent"
```

Her satır `seçenek=değer` biçimindedir (başta `--` olmadan, `#` ile yorum). Tekrarlanabilen seçenekler (`cp`,
`entry`, `target`, `include`, `exclude`) virgülle ayrılabilir; bayraklar `true`/`false` alır. Windows yolları ters
bölüyle olduğu gibi yazılabilir. Komut satırında verilen seçenekler dosyadakilere eklenir (tek değerli olanlarda
komut satırı geçerlidir).

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

Çıktı yalnızca ASCII karakterlerden oluşur (Türkçe harfler ve özel semboller kullanılmaz), böylece Windows
`cmd` penceresinde de düzgün görünür.

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
