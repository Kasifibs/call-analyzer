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

Proje iki modülden oluşur:

- `call-analyzer/`: analiz kütüphanesi ve komut satırı aracı,
- `call-analyzer-maven-plugin/`: aynı analizi bir projenin build'i sırasında çalıştıran Maven plugin'i
  (bkz. [Maven plugin'i](#maven-plugini)).

```bash
mvn install
# -> call-analyzer/target/call-analyzer-1.0.0-SNAPSHOT-all.jar  (bağımlılıkları içeren çalıştırılabilir jar)
# -> plugin yerel Maven deposuna (~/.m2) kurulur
```

Java 17+ gerekir.

## Kullanım

Önce analiz edilecek uygulamayı derleyin (`mvn compile` → `target/classes`), sonra:

```bash
java -jar call-analyzer/target/call-analyzer-1.0.0-SNAPSHOT-all.jar \
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
| `--acquirer <metot>` | Ör. `com.example.CarAcquirer#acquire`. Giriş metodunda bu metoda verilen listedeki `ICar` nesnelerini bulur ve akışta kullanılan sınıflarla karşılaştırır (bkz. aşağıda). |
| `--acquire-annotation <tip>` | Ör. `com.example.AcquireCar`. Analiz edilen (giriş) metotta bu anotasyon var mı bakar, içinde verilen sınıfları listeler ve karşılaştırmaya ekler. `--acquirer` ile birlikte kullanılabilir. |
| `--fail-on-missing` | Karşılaştırmada kesin eksik sınıf bulunursa çıkış kodu `3` olur (CI'da kontrol için). |
| `--event-class`, `--event-dispatcher`, `--event-observer` | Event takibi (bkz. aşağıda). Üçü birlikte verilir. |
| `--event-handle-method <ad>` | Observer'ın event'i alan metodu (varsayılan `handle`). |
| `--event-detection new\|dispatch` | `new` (varsayılan): akışta `new` ile oluşturulan her event yayınlanmış sayılır; `dispatch`: yalnızca dispatcher'a verilenler (bkz. aşağıda). |
| `--event-match exact\|assignable` | `addObserver(this)` ile kaydolan `IObserver<AEvent>` yalnızca `AEvent`'i mi (varsayılan) yoksa alt sınıflarını da mı alır. |
| `--scan` | `--entry` yerine: acquire eden tüm metotları bulup her birini analiz eder (bkz. aşağıda). |
| `--config <dosya>` | Seçenekleri bir ayar dosyasından oku (bkz. aşağıda). |
| `--tree` | Gezilen çağrı ağacını da yazdır. |
| `--format text\|json\|html` | Çıktı biçimi. JSON, CI kontrolleri ya da başka araçlarla işlemek için; HTML, tarayıcıda gezilebilen rapor (bkz. aşağıda). |
| `--open` | Raporu yazdıktan sonra varsayılan tarayıcıda/uygulamada açar (`-o` ile). |
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

## HTML raporu (`--format html`)

Akışı tarayıcıda tıklayarak takip etmek için:

```bat
java -jar call-analyzer-1.0.0-SNAPSHOT-all.jar --config ayar.properties --entry "com.example.RentalService#rent" --format html -o rapor.html --open
```

Tek bir `.html` dosyası üretilir; internet bağlantısı ya da dış kaynak (CDN vb.) gerekmez, dosyayı paylaşabilirsiniz.
Sekmeler:

- **Özet**: sayılar, uyarılar, uygulama sınıfı bazında hangi metotların çağrıldığı.
- **Bulgular**: aranabilir/filtrelenebilir tablo. Bir satıra tıklayınca çağrı zinciri (event geçişleri dahil) açılır;
  "Ağaçta göster" o çağrıyı çağrı ağacında açıp vurgular.
- **Çağrı ağacı**: tıkladıkça açılan ağaç. Varsayılan olarak yalnızca hedef tipe (`ICar`) giden dallar gösterilir;
  "Hedef çağrılarını aç" tüm bu yolları açar, metot araması bulunan metoda giden yolu açar. Event ile devam eden
  çağrılar mor etiketle, özyineleme `↻` ile işaretlenir.
- **Acquire** (`--acquirer` / `--acquire-annotation` ile): eksik, olası eksik, belirsiz ve kullanılmayan sınıflar;
  her çağrı yerinden bulguya ya da ağaca gidilebilir.
- **Eventler** (`--event-*` ile): kayıtlı observer'lar ve akışta yayınlanan event'ler.

Büyük akışlarda da hızlıdır (17.000 metotluk bir akışın raporu ~20 MB, tarayıcıda 1 saniyenin altında açılır).

## Acquire listesi karşılaştırması (`--acquirer`)

Giriş metodunda (genellikle başında) `CarAcquirer.acquire(List<ICar>)` gibi bir çağrı varsa, araç bu listeye konan
`ICar` nesnelerini bulur ve akışta gerçekten çağrılan `ICar` sınıflarıyla karşılaştırır.

Yalnızca giriş metodunun (`--entry`) **kendi** `acquire` çağrıları sayılır (anotasyonla aynı kural). Akışta daha
derinde (bir yardımcı metotta, event ile tetiklenen bir observer'da, ...) yapılan `acquire` çağrıları, giriş metodunun
eksiklerini örtmesin diye karşılaştırmaya alınmaz; raporun sonunda "Bilgi: akistaki diger acquire cagrilari"
başlığı altında listelenir.

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
- Listenin bir değişken ya da yardımcı metot üzerinden gelmesi; listenin kendisi giriş metodunun parametresiyse
  (`--entry` ile verilen metodun dışarıdan aldığı liste) içeriği bilinemez.

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

## Toplu tarama (`--scan`)

Tek tek `--entry` vermek yerine, sınıf yolunda `CarAcquirer.acquire(...)` çağıran **ya da** `@AcquireCar`
anotasyonunu taşıyan tüm metotları bulup her birini ayrı bir giriş metodu olarak analiz eder:

```bat
java -jar call-analyzer-1.0.0-SNAPSHOT-all.jar --cp uygulamam.jar --scan --target com.example.ICar --acquirer "com.example.CarAcquirer#acquire" --acquire-annotation com.example.AcquireCar --include com.example --format html -o tarama.html --open
```

- `--include com.example` hem taranacak metotları kendi kodunuzla sınırlar hem de analizi hızlandırır
  (kütüphanelerin içine girilmez); büyük uygulamalarda mutlaka verin.
- Bir lambda içindeki `acquire` çağrısı (`template.execute(() -> CarAcquirer.acquire(...))`), lambdayı tanımlayan
  metoda aittir: o metot taranır ve çağrı o metodun kendi çağrısı sayılır.
- Diğer tüm seçenekler (event takibi, `--max-depth`, `--direct-only`, ...) her metodun analizine aynen uygulanır.
- `--fail-on-missing` ile herhangi bir metotta kesin eksik varsa çıkış kodu `3` olur.

Metin çıktısı önce bir durum tablosu verir, ardından sorunlu her metodun acquire karşılaştırmasını yazar:

```
=== Toplu acquire taramasi: com.example.CarAcquirer#acquire, @com.example.AcquireCar ===
Taranan metot : 15  (acquire cagrisi: 13, @AcquireCar: 3)
Sonuc         : 4 metotta kesin eksik, 2 metotta yalnizca olasi eksik, 9 metot OK

   #  Durum  Kesin eksik            Olasi eksik/belirsiz   Kullanilmayan      Metot
   9  [X]    TowTruck               -                      -                  com.example.RentalService#rent()  [acquire]
  10  [X]    Tesla                  -                      -                  com.example.RentalService#rentAnnotated()  [anotasyon]
  12  [?]    -                      Tesla,TowTruck         -                  com.example.RentalService#rentOnlyBmw()  [acquire]
  13  [OK]   -                      -                      Tesla              com.example.RentalService#rentTooMuch()  [acquire]
  ...
```

`--format html -o tarama.html` bir özet sayfası (`tarama.html`: durum kartları, aranabilir/filtrelenebilir tablo) ve
yanındaki `tarama_metotlar/` klasöründe her metot için tam bir rapor üretir; tablodaki metot adına tıklayınca o
metodun raporu (çağrı ağacı, bulgular, acquire) açılır, oradan "← Tüm metotlar" ile geri dönülür. JSON çıktısında her
metodun durumu ve tam acquire karşılaştırması bulunur.

Bir `acquire` çağrısına verilen liste metodun parametresiyse ve akışta onu dolduran bir çağıran yoksa (ör. yardımcı
bir `acquireAll(List<ICar> cars)` metodu), liste içeriği bilinemez; raporda `?` eleman olarak görünür.

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

### Event'lerin tespiti (`--event-detection`)

- **`new` (varsayılan):** Akışta çalışan metotlarda `new XDomainEvent(...)` ile oluşturulan **her event'in yayınlandığı
  varsayılır**; akış, event'in oluşturulduğu yerden o event'i dinleyen observer'ların `handle` metoduna devam eder
  (zincirde `[event XDomainEvent]`, ağaçta `[yayin] new XDomainEvent()`). Dispatcher'ın kendisinin içine girilmez.
  Event'in dispatcher'a gerçekten verilip verilmediğine yalnızca **doğrulama** için bakılır:
  - akışta event'i yayınlayan bir `notify(...)` çağrısı bulunursa: `yayin: notify ile dogrulandi (Dosya.java:satir)`,
  - bulunamazsa: `yayin: DOGRULANAMADI` — bu event **varsayımla** yayınlanmış sayılmıştır. Bu event üzerinden ulaşılan
    bulgular Bulgular'da ve acquire karşılaştırmasında **`EVENT VARSAYIMI` / `[event varsayimi]`** ile işaretlenir
    (HTML'de "event varsayımı" etiketi ve "Event varsayımıyla ulaşılanları gizle" filtresi).
- **`dispatch`:** Yalnızca dispatcher'ın yayın metoduna (`notify(event)`, `dispatch(event)`) verilen event'ler sayılır;
  event'in sınıfı veri akışıyla bulunur (aşağıda). Event belirlenemezse akış kayıtlı tüm observer'lara devam eder ve
  bu da varsayım olarak işaretlenir.

Observer seçimi (aşağıdaki kayıt tablosu ve tip kontrolü) iki modda da aynıdır.

### Dispatch modunda event'in bulunması

Event ayarı olmadan araç `dispatcher.dispatch(event)` içindeki `observer.handle(event)` çağrısında **tüm**
`IObserver` uygulamalarına iner. Event ayarıyla:

1. Sınıf yolundaki tüm kayıt çağrıları taranır (EventDispatcher'ın `IObserver` parametresi alan, adı `un`/`remove`
   ile başlamayan metotları: `addObserver`, `register`, `subscribe`, ...):
   - **Adsız kayıt** (`dispatcher.addObserver(this)`): observer, dinlediği event **tipine** göre kaydedilir. Tip,
     `IObserver<ADomainEvent>` → `handle(ADomainEvent)` parametresinden; handle generic bir taban sınıftaysa
     (`AHandler extends BaseHandler<ADomainEvent>`) sınıfın generic imzasından okunur.
   - **Adlı kayıt** (`dispatcher.register("CAR_RENTED", this)`): observer o **ada** kaydedilir (ad bir sabit ya da
     `static final` alan olmalı). Ad çözülemezse observer "adı bilinmiyor" olarak kaydedilir ve uyarı verilir.
2. Akışta bir `dispatch(event)` görüldüğünde event'in sınıfı (ve varsa adı: `super("CAR_RENTED")`, `new X("ad")`,
   sabit dönen `getName()`; üst sınıf constructor zinciri takip edilir) veri akışıyla bulunur. Event bir yardımcı
   metoda parametre olarak geçiyorsa (`publish(DomainEvent e)`) akıştaki çağıranlara bakılır.
3. Akış yalnızca o event'e kayıtlı observer'ların `handle` metoduna devam eder:
   - adsız kayıtlarda `IObserver<T>` tipi event sınıfıyla eşleşenler (`--event-match exact`, varsayılan:
     `IObserver<AEvent>` yalnızca `AEvent` alır; `assignable`: alt sınıflarını da alır),
   - adlı kayıtlarda o ada kayıtlı olanlar,
   - `handle(T)` parametresi event'i kabul etmeyen observer **hiçbir durumda** seçilmez (gerçekte
     `ClassCastException` olurdu); adı bilinmeyen kayıtlar da bu kontrolden geçer.
   Observer'ın içinde yayınlanan event'ler (inner event) de aynı şekilde takip edilir.

Event'in nereden geldiği şu kalıplarda da bulunur:

- `for (DomainEvent e : aggregate.pullEvents()) dispatcher.dispatch(e)`,
  `aggregate.pullEvents().forEach(dispatcher::dispatch)` ya da `.forEach(e -> dispatcher.dispatch(e))`: event'ler
  listenin elemanlarıdır. Bir alana (ör. aggregate'in `events` listesi) eklenen event'ler yalnızca **analiz edilen
  akışta çalışan** metotlardan (ve constructor'lardan) toplanır: akışta `car.rent()` çağrılıyorsa `rent()`'in eklediği
  event sayılır, hiç çağrılmayan `scrap()`'in eklediği sayılmaz.
- Event'ler yönlendirilmeden önce akışın geri kalanı incelenir (örn. `car.rent(); ...dispatch(...)` sırasında önce
  `rent()`'in içi), yeni bulunan observer'ların içindeki event'ler için de tekrarlanır.

Event'in sınıfı hiç belirlenemiyorsa (ör. giriş metodunun parametresi) **kayıtlı** tüm observer'lara gidilir; raporda
bu "Secim" satırında belirtilir. Kaydı hiç bulunamayan observer'lar hiçbir event'te tetiklenmez sayılır ve uyarı olarak
listelenir.

Çıktıda bulguların zincirinde event geçişleri görünür, ayrıca bir event bölümü eklenir. Bölüm, akışta tespit edilen
yayınlanmış event'lerin listesiyle başlar (hangi event, hangi `dispatch` satırında yayınlandığı ve event nesnesinin
nerede oluşturulduğu; belirlenemeyenler en sonda `? (belirlenemedi)` olarak):

```
Akista yayinlanan event'ler (3 tip):
    CarRentedEvent           E1  EventFlowService#rent()  (EventFlowService.java:12)
                                 <- new CarRentedEvent() (EventFlowService.java:12)
    TyreChangedEvent         E2  EventFlowService#aggregateMethodRef()  (EventFlowService.java:64)
                                 <- new TyreChangedEvent() (CarAggregate.java:11)
    ? (belirlenemedi)        E3  EventFlowService#unknownEvent(Event)  (EventFlowService.java:32)
                                 <- EventFlowService#unknownEvent(Event) metodunun 1. parametresi 'event'
```

HTML raporunda da Özet sekmesinde yayınlanan event'ler, Eventler sekmesinde aynı liste "ağaçta göster"
bağlantılarıyla bulunur. Bölümün devamı:

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

## Maven plugin'i

Analizi projenizin build'inin bir parçası yapar: jar oluştuktan sonra (`verify` fazında) projenizdeki acquire eden
**tüm metotları** bulur (`--scan` ile aynı), her birinin akışını analiz eder ve **kesin eksik acquire olan bir metot
varsa build'i başarısız yapar**.

Önce plugin'i yerel Maven deponuza kurun (bu projenin kökünde `mvn install`; şirket içi bir Nexus/Artifactory'ye
`mvn deploy` ile de konabilir). Sonra analiz edilecek projenin (çok modüllü ise uygulamayı paketleyen modülün)
`pom.xml`'ine ekleyin:

```xml
<plugin>
    <groupId>io.github.kasifibs</groupId>
    <artifactId>call-analyzer-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>analyze</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <targets>
            <target>com.sirket.domain.ICar</target>
        </targets>
        <acquirer>com.sirket.domain.CarAcquirer#acquire</acquirer>
        <acquireAnnotation>com.sirket.domain.AcquireCar</acquireAnnotation>
        <includes>
            <include>com.sirket</include>
        </includes>
        <!-- event takibi (istege bagli) -->
        <eventClass>com.sirket.domain.DomainEvent</eventClass>
        <eventDispatcher>com.sirket.application.DomainEventDispatcher</eventDispatcher>
        <eventObserver>com.sirket.domain.IObserver</eventObserver>
    </configuration>
</plugin>
```

`mvn verify` (ya da `mvn install`) sırasında:

```
[INFO] --- call-analyzer:1.0.0-SNAPSHOT:analyze (default) @ app ---
[INFO] call-analyzer: app-1.0.jar analiz ediliyor (1 bagimlilik sinif yolunda)
[INFO] call-analyzer: acquire eden 1 metot bulundu
[INFO] call-analyzer: 1 metot: 1 kesin eksik, 0 olasi eksik, 0 OK
[ERROR]   [X] com.example.app.RentalService#rent()  kesin eksik: Tesla
[INFO] call-analyzer raporu: .../app/target/call-analyzer/tarama.html
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal io.github.kasifibs:call-analyzer-maven-plugin:1.0.0-SNAPSHOT:analyze (default) on project app:
        call-analyzer: 1 metotta acquire edilmeden kullanilan sinif var (kesin eksik). Ayrintilar: .../tarama.html
```

- **Ne analiz edilir:** projenin ürettiği jar (yoksa `target/classes`). Projenin çalışma zamanı bağımlılıkları (çok
  modüllü projede diğer modüller dahil) sınıf yoluna otomatik eklenir, akış onların içine de girer; ancak acquire
  eden metotlar yalnızca **projenin kendi sınıflarında** aranır.
- **Raporlar:** `target/call-analyzer/` altında `tarama.html` (+ `tarama_metotlar/` içinde metot başına sayfa),
  `tarama.txt`, `tarama.json`.
- **Build ne zaman başarısız olur:** kesin eksik varsa (`failOnMissing`, varsayılan `true`). Olası eksikler için de
  başarısız olsun isterseniz `<failOnPossiblyMissing>true</failOnPossiblyMissing>`.
- **Atlamak için:** `mvn verify -Dcallanalyzer.skip=true`; uyarı verip build'i geçirmek için
  `-Dcallanalyzer.failOnMissing=false`.

Tüm ayarlar:

| Ayar | Varsayılan | Açıklama |
|---|---|---|
| `targets` | (zorunlu) | Uygulayıcıları aranan tip(ler), ör. `com.sirket.domain.ICar`. |
| `acquirer` / `acquireAnnotation` | - | En az biri zorunlu. |
| `includes` / `excludes` | - | Taranacak ve içine girilecek paketler. `includes` olarak kendi kök paketinizi vermeniz önerilir (hız). |
| `eventClass`, `eventDispatcher`, `eventObserver` | - | Event takibi (üçü birlikte). |
| `eventHandleMethod` | `handle` | |
| `eventDetection` | `new` | `new` ya da `dispatch`. |
| `eventMatch` | `exact` | `exact` ya da `assignable`. |
| `maxDepth`, `directOnly`, `onlyInterfaceMethods`, `skipInternal`, `includeConstructors` | | Komut satırındaki karşılıklarıyla aynı. |
| `failOnMissing` | `true` | Kesin eksikte build'i başarısız yap (`-Dcallanalyzer.failOnMissing`). |
| `failOnPossiblyMissing` | `false` | Olası eksikte de başarısız yap. |
| `includeDependencies` | `true` | Bağımlılıkları sınıf yoluna ekle. `false` ile yalnızca projenin kendi jar'ı okunur (çok daha hızlı), ama akışın başka modüllere geçen kısımları takip edilmez. |
| `warnMissingClasses` | `true` | Akış, `includes` paketlerinde olup sınıf yolunda bulunmayan bir sınıfa geçince uyar. `includeDependencies=false` iken metot başına değil tek satırlık özet olarak yazılır; `false` ile yalnızca `mvn -X` çıktısında görünür (`-Dcallanalyzer.warnMissingClasses`). |
| `analyzedFile` | proje jar'ı | Başka bir jar/dizini analiz etmek için. |
| `reportDirectory` | `target/call-analyzer` | |
| `htmlReport` | `true` | HTML raporu da yaz. |
| `skip` | `false` | `-Dcallanalyzer.skip=true`. |

Plugin pom'da tanımlıysa (ayarlar yukarıdaki gibi `<execution>` dışındaki `<configuration>`'da), build'in geri
kalanını çalıştırmadan da çağrılabilir: `mvn package call-analyzer:analyze` (ya da tam adıyla
`mvn package io.github.kasifibs:call-analyzer-maven-plugin:1.0.0-SNAPSHOT:analyze`).

## Testler

```bash
mvn verify
```

Analiz kütüphanesinin birim testlerine ek olarak, plugin `call-analyzer-maven-plugin/src/it/` altındaki örnek
projeleri gerçekten derleyip plugin'i çalıştırır (biri geçen, biri kesin eksik nedeniyle başarısız olması beklenen
çok modüllü bir proje, ikisi de `includeDependencies=false` iken uyarıların özetlendiğini/kapatıldığını kontrol eden).
