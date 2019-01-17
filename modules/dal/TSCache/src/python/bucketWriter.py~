import avro.schema
from avro.datafile import DataFileWriter
from avro.io import DatumWriter
import csv
import calendar
import time

schema = avro.schema.parse(open("weather.avsc").read())

writer_null = DataFileWriter(open("weather_data_null.avro", "wb"), DatumWriter(), schema, codec="null")
writer_deflate = DataFileWriter(open("weather_data_deflate.avro", "wb"), DatumWriter(), schema, codec="deflate")
#writer_snappy = DataFileWriter(open("weather_data_snappy.avro", "wb"), DatumWriter(), schema, codec="snappy")

# Header: Date/Time,Temp (C),Dew Point Temp (C),Rel Hum (%),Wind Spd (km/h),Visibility (km),Stn Press (kPa),Weather
fields = "time temp dew_point_temp humidity wind_speed visibility pressure weather".split()
headers = dict([(v,i) for i,v in enumerate(fields)])

with open("./../../data/weather_2012.csv") as csvfile:
    reader = csv.reader(csvfile)
    reader.next() # skip header
    for boring_row in reader:
        row = dict(zip(fields, boring_row))

        # convert fields to right type
        row["time"] = int(time.mktime((time.strptime(row["time"], "%Y-%m-%d %H:%M:%S"))))
        
        for int_field in "humidity wind_speed".split():
            row[int_field] = int(row[int_field])

        for float_field in "temp dew_point_temp visibility pressure".split():
            row[float_field] = float(row[float_field])

        writer_null.append(row)
        writer_deflate.append(row)
#        writer_snappy.append(row)

writer_null.close()
writer_deflate.close()
#writer_snappy.close()
