def log = new File(basedir, "build.log").text;
assert log =~ /Aritfact com.example:no-source:jar:0.0.1 was not built!/;
