// Only the matching dependency (the domain module) is on the class path, so the flow into it still finds Tesla.
String text = new File(basedir, "build.log").text
assert text.contains("call-analyzer: app-1.0.jar analiz ediliyor (1/2 bagimlilik sinif yolunda: domain-1.0.jar)")
assert text.contains("<dependencyInclude>com.example.it:nope</dependencyInclude> hicbir bagimlilikla eslesmedi")
assert text.contains("[X] com.example.app.RentalService#rent()  kesin eksik: Tesla")
return true
