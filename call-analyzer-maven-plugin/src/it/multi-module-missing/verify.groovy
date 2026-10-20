File log = new File(basedir, "build.log")
String text = log.text
assert text.contains("[X] com.example.app.RentalService#rent()  kesin eksik: Tesla")
assert text.contains("call-analyzer: 1 metotta acquire edilmeden kullanilan sinif var (kesin eksik)")
assert new File(basedir, "app/target/call-analyzer/tarama.html").isFile()
return true
