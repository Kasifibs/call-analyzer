// Only the app jar is on the class path: the flow into the domain module stops at Charger, so Tesla is not seen,
// and the missing class is reported once as a summary instead of per method.
String text = new File(basedir, "build.log").text
assert text.contains("(0 bagimlilik sinif yolunda)")
assert text.contains("[WARNING] call-analyzer: 1 metotta akis sinif yolunda olmayan siniflara geciyor")
assert !text.contains("OLMAYAN")
assert text.contains("call-analyzer: 1 metot: 0 kesin eksik")
return true
