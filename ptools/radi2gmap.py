#!/usr/bin/python
# -*- coding: utf-8 -*-

import csv
import sys
import argparse
import cgi
import cgitb; cgitb.enable()
import glob

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
        if r>1.0: r=1.0
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

class GmapScript():
    def __init__(self):
        self.dataindex=0
        self.latmax=-90.0
        self.latmin=90.0
        self.longmax=-180.0
        self.longmin=180.0
        self.lastpoint=(35.660426,139.324963)

    def latlong_maxmin(self, lat, lng):
        if lat>self.latmax: self.latmax=lat
        if lat<self.latmin: self.latmin=lat
        if lng>self.longmax: self.longmax=lng
        if lng<self.longmin: self.longmin=lng
        self.lastpoint=(lat,lng)

    def write_header(self):
        print """<script>
var dosemap = new Array();
"""

    def write_contents(self, rcf, hue):
        i=0
        print "dosemap[%d] = new Array();" % self.dataindex
        for row in rcf:
            print "dosemap[%d][%d] = {center: new google.maps.LatLng(%s, %s)," \
                % (self.dataindex, i, row["Latitude"], row["Longitude"]),
            print 'doserate: %s, colordose: "%s"};' % \
                (row["DoseRate"], hue.rgb_string(float(row["DoseRate"])))
            self.latlong_maxmin(float(row["Latitude"]), float(row["Longitude"]))
            i+=1
        self.dataindex+=1

    def write_footer(self, radius):
        print """
var doseCircle;
function initialize() {
  var mapOptions = {
    zoom: 16,
"""
        print "center: new google.maps.LatLng(%s, %s)," % \
        (self.lastpoint[0], self.lastpoint[1])

        print """
    mapTypeId: google.maps.MapTypeId.ROADMAP
  };
  var map = new google.maps.Map(document.getElementById('map-canvas'),
      mapOptions);

  for (j=0;j<dosemap.length;j++) {
    for (i=0;i<dosemap[j].length;i++) {
      var doserateOptions = {
        strokeColor: '#FF0000',
        strokeOpacity: 1.0,
        strokeWeight: 0,
        fillColor: dosemap[j][i].colordose,
        fillOpacity: 1.0,
        map: map,
        center: dosemap[j][i].center,
"""
        print "        radius: %d" % radius
        print """
      };
    // Add the circle for this dose to the map.
      doseCircle = new google.maps.Circle(doserateOptions);
    }
  }
}
google.maps.event.addDomListener(window, 'load', initialize);
    </script>
"""

class radi2gmap_cgifields():
    rinf = None
    ufilename = None
    datatypes={'swalk':False, 'walk':False, 'bike':False, 'motor':False}
    tname = 'RadiLogWalkerData'
    maxvalue = 0.2
    radius = 20
    datadir = '/var/www/hachisoku/pdata/radilog'
    fg=None
    def __init__(self):
        if len(sys.argv)<=1:
            form = cgi.FieldStorage()
            if 'file' in form:
                ufile = form['file']
                try:
                    self.rinf = ufile.file
                    self.ufilename = ufile.filename
                except:
                    self.rinf = None
            for dt in self.datatypes:
                if dt in form: self.datatypes[dt]=form[dt].value
            if 'tname' in form: self.tname = form['tname'].value
            if 'maxvalue' in form: self.maxvalue = float(form['maxvalue'].value)
            if 'radius' in form: self.radius = int(form['radius'].value)
            if 'datadir' in form: self.datadir = form['datadir'].value
            return

        parser=argparse.ArgumentParser()
        parser.add_argument('-f', '--file')
        parser.add_argument('-s', '--swalk', action='store_true')
        parser.add_argument('-w', '--walk', action='store_true')
        parser.add_argument('-b', '--bike', action='store_true')
        parser.add_argument('-m', '--motor', action='store_true')
        parser.add_argument('-t', '--tname')
        parser.add_argument('-x', '--maxvalue', type=float)
        parser.add_argument('-r', '--radius', type=int)
        parser.add_argument('-d', '--datadir')
        paras=parser.parse_args()
        if paras.file:
            self.rinf=open(paras.file,"r")
            self.ufilename = paras.file
        if paras.swalk: self.datatypes['swalk']=paras.swalk
        if paras.walk: self.datatypes['walk']=paras.walk
        if paras.bike: self.datatypes['bike']=paras.bike
        if paras.motor: self.datatypes['motor']=paras.motor
        if paras.tname: self.tname=paras.tname
        if paras.maxvalue: self.maxvalue=float(paras.maxvalue)
        if paras.radius: self.radius=int(paras.radius)
        if paras.datadir: self.datadir=paras.datadir

    def __iter__(self):
        return self.fgen()

    def fgen(self):
        if self.rinf and self.ufilename:
            yield self.rinf
            self.rinf.close()
        for dt in self.datatypes:
            if self.datatypes[dt]:
                fpath='/'.join([self.datadir,dt])
                flist=glob.glob('%s/*.csv' % fpath)+glob.glob('%s/*.CSV' % fpath)
                for f in flist:
                    self.rinf=open(f,"r")
                    yield self.rinf
                    self.rinf.close()


def file_upload_form():
    res = """<form enctype="multipart/form-data" action="" method="post">
  <p><b>CSVファイルをアップロードして表示させる場合</b><br/>
  CSV File: <input type="file" name="file" /></p>
  <p><b>サーバーにあるデータを表示させる場合</b><br/>
  <input type="checkbox" name="swalk" value="swalk" />RadilogWalker（鉛遮蔽あり）歩行測定データ</br>
  <input type="checkbox" name="walk" value="walk" />歩行測定データ</br>
  <input type="checkbox" name="bike" value="bike" />自転車測定データ</br>
  <input type="checkbox" name="motor" value="motor" />自動車測定データ
  </p>
  <!-- p>タイトル（なくてもいい）: <input type="text" name="tname" /></p -->
  <p>最高線量: <input type="text" name="maxvalue" value="0.2"/></p>
  <p>マーカーの半径: <input type="text" name="radius" value="20"/></p>
  <p><button name="Upload" type="submit" value="Upload" style="width:5em" />表示</button></p>
  <p>CSVファイルをアップロードして表示させる場合、1行目のフィールド名に次の3項目が必要です。<br/>
  Latitude,Longitude,DoseRate<br/><br/>
  Latitude,Longitudeは小数点表示の緯度、経度。<br/>
  DoseRateは小数点表示のuSv/h単位の線量。<br/>
  </p>
  </form>
"""
    return res

if __name__ == '__main__':
    
    cgif=radi2gmap_cgifields()

    print html_header()
    hue=HueExpression(vmax=cgif.maxvalue)
    gmaps=GmapScript()
    gmaps.write_header()
    fnum=0
    dataerror=False
    for inf in cgif:
        rcf=csv.DictReader(inf)
        if 'Latitude' not in rcf.fieldnames or \
                'Longitude' not in rcf.fieldnames or \
                'DoseRate' not in rcf.fieldnames:
            dataerror=True
            continue
        gmaps.write_contents(rcf, hue)
        fnum+=1

    gmaps.write_footer(cgif.radius)
    print "</head><body>"
    if fnum>0:
        print '<div style="z-index:1;" id="map-canvas"></div>'
        hue.html_legend()
    else:
        if dataerror:
            print '<p style="color:red">CSVファイルのフォーマットが正しくありません</p>'
        print file_upload_form()

    print "</body>"
    print html_footer()
