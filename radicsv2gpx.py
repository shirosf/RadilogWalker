#!/usr/bin/python
import sys
import time

class RadiCsvFile():
    tags={"Latitude":0,"Longitude":0,"DoseRate":0,"GpsDateTime":0,"DoseDateTime":0}
    ritems=None
    def __init__(self, fname):
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

            
if __name__ == '__main__':
    name='RadiLogData'
    if len(sys.argv)>2: name=sys.argv[2]

    rcf=RadiCsvFile(sys.argv[1])
    if not rcf.inf: sys.exit(1)
    print """<?xml version="1.0" encoding="UTF-8"?>
<gpx
  version="1.0"
  creator="GPSBabel - http://www.gpsbabel.org"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns="http://www.topografix.com/GPX/1/0"
  xsi:schemaLocation="http://www.topografix.com/GPX/1/0 http://www.topografix.com/GPX/1/0/gpx.xsd">
"""
    print '<trk><name>%s</name>' % name
    print '<trkseg>'
    while True:
        if not rcf.get_nextline(): break
        print '<trkpt lat="%s" lon="%s"><ele>%d</ele><time>%s</time></trkpt>' % ( \
            rcf.get_item("Latitude"),
            rcf.get_item("Longitude"),
            int(float(rcf.get_item("DoseRate"))*1000),
            rcf.conv_to_utc(rcf.get_item("DoseDateTime")) )
    print '</trkseg></trk>'
    print '</gpx>'
    rcf.close()


