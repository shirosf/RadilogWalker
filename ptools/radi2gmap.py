#!/usr/bin/python
# -*- coding: utf-8 -*-

import radicsv2gpx
import sys
import cgi
import cgitb; cgitb.enable()

def html_header():
    res = """Content-Type: text/html

<!DOCTYPE html>
<html>
  <head>
    <meta name="viewport" content="initial-scale=1.0, user-scalable=no">
    <meta charset="utf-8">
    <title>Circles</title>
    <style>
      html, body, #map-canvas {
        height: 100%;
        margin: 0px;
        padding: 0px
      }
    </style>
    <script src="https://maps.googleapis.com/maps/api/js?v=3.exp&sensor=false"></script>
"""
    return res

def html_footer():
    res = """
</html>
"""
    return res

class HueExpression():
    def __init__(self, vmin=0.0, vmax=0.20, hstart=90.0, hend=330.0):
        self.value_min=vmin
        self.value_max=vmax
        self.hue_start=hstart
        self.hue_end=hend
    
    def html_legend(self, steps=10):
        print """
<div style="font:11px Arial; border:solid #666666 1px; background:#ffffff; padding:4px; overflow:auto; width:60px; float:left; position: absolute; bottom: 0; z-index:1;">
    <div style="padding-bottom:2px;"><b>Doserate<br/>(uSv/h)</b></div>
"""
        sv=(self.value_max-self.value_min)/steps
        for i in range(steps, 0, -1):
            print '<div class="gv_legend_item"><span style="color:%s;">&#9608; %.3f</span></div>' % \
                (self.rgb_string(sv*i), sv*i)
        print "</div>"


    def dose_to_hue(self, value):
        r=(value-self.value_min)/(self.value_max - self.value_min)
        if r<0.0: r=0.0
        return r*(self.hue_end - self.hue_start) + self.hue_start

    def hsv_to_rgb(self, h, s=1.0, v=1.0):
        if s==0: return (v,v,v)
        h /= 60
        i = int(h)
        f = h - i
        p = v * ( 1.0 - s )
        q = v * ( 1.0 - s * f )
        t = v * ( 1.0 - s * ( 1.0 - f ) )
        rval=[(v,t,p),(q,v,p),(p,v,t),(p,q,v),(t,p,v),(v,p,q)]
        return rval[i];

    def rgb_string(self, value):
        h=self.dose_to_hue(value)
        r,g,b=self.hsv_to_rgb(h)
        return "#%02X%02X%02X" % (r*255,g*255,b*255)


def write_contents_script(rcf, hue):
    print """<script>
var dosemap = new Array();
"""
    
    i=0
    while rcf.get_nextline():
        print "dosemap[%d] = {center: new google.maps.LatLng(%s, %s)," \
            % (i, rcf.get_item("Latitude"), rcf.get_item("Longitude")),
        print 'doserate: %s, colordose: "%s"};' % \
            (rcf.get_item("DoseRate"), hue.rgb_string(float(rcf.get_item("DoseRate"))))
        i+=1

    print """
var doseCircle;
function initialize() {
  var mapOptions = {
    zoom: 16,
"""
    print "center: new google.maps.LatLng(%s, %s)," % \
        (rcf.get_item("Latitude"), rcf.get_item("Longitude"))

    print """
    mapTypeId: google.maps.MapTypeId.ROADMAP
  };
  var map = new google.maps.Map(document.getElementById('map-canvas'),
      mapOptions);

  for (i=0;i<dosemap.length;i++) {
    var doserateOptions = {
      strokeColor: '#FF0000',
      strokeOpacity: 1.0,
      strokeWeight: 0,
      fillColor: dosemap[i].colordose,
      fillOpacity: 1.0,
      map: map,
      center: dosemap[i].center,
      radius: 20
    };
    // Add the circle for this dose to the map.
    doseCircle = new google.maps.Circle(doserateOptions);
  }
}
google.maps.event.addDomListener(window, 'load', initialize);
    </script>
"""

def cgi_fields():
    form = cgi.FieldStorage()
    ufile = None
    if 'file' in form: ufile = form['file']
    tname = form.getvalue('tname',"")
    maxvalue = form.getvalue('maxvalue','0.2')
    return ufile, tname, float(maxvalue)

def file_upload_form():
    res = """Content-Type: text/html

<html>
  <head>
    <meta http-equiv="Content-type" content="text/html; charset=utf-8" />
  </head>
  <body>
  <form enctype="multipart/form-data" action="" method="post">
  <p>CSV File: <input type="file" name="file" /></p>
  <p>タイトル（なくてもいい）: <input type="text" name="tname" /></p>
  <p>最高線量: <input type="text" name="maxvalue" value="0.2"/></p>
  <p><input type="submit" value="Upload" /></p>
  </form>
  </body>
</html>
"""
    return res

if __name__ == '__main__':
    tname='RadiLogWalkerData'
    uinf = None
    if len(sys.argv)<2:
        ufile, name, vmax = cgi_fields()
        if name: tname=name
        try:
            uinf = ufile.file
        except:
            pass
        ufname = None
    else:
        ufname=sys.argv[1]
        if len(sys.argv)>2: tname=sys.argv[2]

    if not ufname and not uinf:
        print file_upload_form()
        sys.exit(0)

    print html_header()
    rcf=radicsv2gpx.RadiCsvFile(ufname, uinf)
    hue=HueExpression(vmax=vmax)
    if not rcf.inf: sys.exit(1)
    write_contents_script(rcf, hue)
    print "</head><body>"
    print '<div style="z-index:1;" id="map-canvas"></div>'
    hue.html_legend()
    print "</body>"
    print html_footer()
    rcf.close()
