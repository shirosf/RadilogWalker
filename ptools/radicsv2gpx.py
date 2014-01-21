#!/usr/bin/python
import sys
import time
import cgi
import cgitb; cgitb.enable()

class RadiCsvFile():
    tags={"Latitude":0,"Longitude":0,"DoseRate":0,"GpsDateTime":0,"DoseDateTime":0}
    ritems=None
    def __init__(self, fname, infile=None):
        if infile:
            self.inf=infile
        else:
            self.inf=open(fname, 'r')
        line=self.get_nextline()
        if line=="": return self.nodata_in_file()
        if len(self.tags) != len(self.ritems):
            self.wrong_file(line)
            return
        for i,s in enumerate(self.ritems):
            try:
                self.tags[s]=i;
            except:
                self.wrong_file(line)
                return
        self.ritems=None
        return
    
    def get_nextline(self):
        line=self.inf.readline()
        if line=="": return None
        if line[-1]=="\n": line=line[0:-1]
        self.ritems=line.split(',');
        return line

    def wrong_file(self, line):
        print "The first line : %s" % line
        print "looks not a RadiLogWalker log file"
        self.close()
        return

    def nodata_in_file(self):
        print "No data in the file"
        self.close()
        return

    def close(self):
        self.inf.close()
        self.inf=None

    def get_item(self, item):
        if not self.ritems: return None
        return self.ritems[self.tags[item]]

    def conv_to_utc(self, dt):
        return time.strftime("%Y-%m-%dT%H:%M:%SZ", 
                             time.gmtime(time.mktime(time.strptime(dt, "%Y-%m-%d %H:%M:%S"))))

    def next_dataline_in_gpx(self):
        if not self.get_nextline(): return None
        return '<trkpt lat="%s" lon="%s"><ele>%d</ele><time>%s</time></trkpt>' % ( \
            self.get_item("Latitude"),
            self.get_item("Longitude"),
            int(float(self.get_item("DoseRate"))*1000),
            self.conv_to_utc(self.get_item("DoseDateTime")) )
        

def gpx_header(name):
    res="""<?xml version="1.0" encoding="UTF-8"?>
<gpx
  version="1.0"
  creator="GPSBabel - http://www.gpsbabel.org"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns="http://www.topografix.com/GPX/1/0"
  xsi:schemaLocation="http://www.topografix.com/GPX/1/0 http://www.topografix.com/GPX/1/0/gpx.xsd">
"""
    res+='<trk><name>%s</name>\n' % name
    res+='<trkseg>'
    return res

def gpx_footer():
    res = '</trkseg></trk>\n'
    res += '</gpx>'
    return res

def html_header():
    res = """Content-Type: text/html

<html>
  <head>
    <meta http-equiv="Content-type" content="text/html; charset=utf-8" />
  </head>
  <body>
  <form enctype="multipart/form-data" action="" method="post">
  <p>File: <input type="file" name="file" /></p>
  <p>Name: <input type="text" name="tname" /></p>
  <p><input type="submit" value="Upload" /></p>
  </form>
"""
    return res

def html_footer():
    return "</body></html>"

def cgi_fields():
    form = cgi.FieldStorage()
    ufile = None
    if 'file' in form: ufile = form['file']
    tname = form.getvalue('tname',"")
    return ufile, tname

if __name__ == '__main__':
    tname='RadiLogWalkerData'
    uinf = None
    if len(sys.argv)<2:
        ufile, name = cgi_fields()
        if name: tname=name
        print html_header()
        if not ufile: uinf = ufile.file
        ufname = None
    else:
        ufname=sys.argv[1]
        if len(sys.argv)>2: tname=sys.argv[2]

    if not ufname and not uinf:
        print html_footer()
        sys.exit(0)

    rcf=RadiCsvFile(ufname, uinf)
    if not rcf.inf: sys.exit(1)
    rdata=gpx_header(tname)+'\n'
    while True:
        nl=rcf.next_dataline_in_gpx()
        if not nl: break
        rdata+=nl+'\n'
    rdata+=gpx_footer()
    
    if ufname:
        print rdata
    else:
        open('output.gpx','w').write(rdata)
        print '<a href="output.gpx">Download the converted file</a>'
        print html_footer()

    rcf.close()
