// warnMissingClasses=false: neither the summary nor the per-method warning is logged.
String text = new File(basedir, "build.log").text
assert text.contains("(0 bagimlilik sinif yolunda)")
assert !text.contains("sinif yolunda olmayan")
assert !text.contains("OLMAYAN")
assert text.contains("call-analyzer: 1 metot: 0 kesin eksik")
return true
