// Tesla is used without being acquired, but the exemption allows it for RentalService#rent: no failure.
String text = new File(basedir, "build.log").text
assert text.contains("[istisna] com.example.app.RentalService#rent()  acquire edilmeden izin verilen: Tesla")
assert text.contains("call-analyzer: 1 metot: 0 kesin eksik, 0 olasi eksik, 1 OK")
assert !text.contains("Istisna analiz edilen hicbir metotla eslesmedi")
assert new File(basedir, "app/target/call-analyzer/tarama.json").text.contains('"exempt": [\n')
return true
