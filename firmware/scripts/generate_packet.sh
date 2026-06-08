#!/bin/sh

echo "------- Begin to generate the packet of burning -------"

today=`date --date='0 days ago' +%Y%m%d-%H%M%S`
dir=temp_dir
project=ar1
ver=$BUILD_NUMBER
#mode=userdebug
mode=$TARGET_BUILD_VARIANT
if [ -z "$mode" ]; then
    mode=userdebug
fi

kernel_mode=$1
if [ -z "$kernel_mode" ];then
  kernel_mode=defconfig
fi

echo "------- create project file $project -------"
mkdir $dir

echo "------- copy $project boot and modem files -------"
cp device/qcom/neo/boot_bin/* $dir/
rm -rf $dir/rawprogram0_factory.xml
rm -rf $dir/ebg_feature_config/

if [ "${ROKID_FACTORY}" = "true" ]; then
	cp -f device/qcom/neo/boot_bin/rawprogram0_factory.xml $dir/rawprogram0.xml
fi

if [ "${ROKID_EBG_STORAGE_FEATURE}" = "true" ]; then
  cp -f device/qcom/neo/boot_bin/ebg_feature_config/rawprogram0.xml $dir/rawprogram0.xml
  cp -f device/qcom/neo/boot_bin/ebg_feature_config/patch0.xml $dir/patch0.xml
  cp -f device/qcom/neo/boot_bin/ebg_feature_config/gpt_backup0.bin $dir/gpt_backup0.bin
  cp -f device/qcom/neo/boot_bin/ebg_feature_config/gpt_main0.bin $dir/gpt_main0.bin
  if [ "${ROKID_FACTORY}" = "true" ]; then
	  cp -f device/qcom/neo/boot_bin/ebg_feature_config/rawprogram0_factory.xml $dir/rawprogram0.xml
  fi
fi

cp kernel_platform/out/neo_la-$kernel_mode/dist/vmlinux $dir/

echo "------- copy Burn Files to $project Firmware -------"
cp out/target/product/neo/boot.img $dir/
cp out/target/product/neo/dtbo.img $dir/
cp out/target/product/neo/persist.img $dir/
cp out/target/product/neo/recovery.img $dir/
cp out/target/product/neo/metadata.img $dir/
cp out/target/product/neo/super.img $dir/
cp out/target/product/neo/userdata.img $dir/
cp out/target/product/neo/abl.elf $dir/
cp out/target/product/neo/vendor_boot.img $dir/
cp out/target/product/neo/vbmeta.img $dir/
cp out/target/product/neo/vbmeta_system.img $dir/

echo "------- entey the $project firmware -------"
cd $dir/

echo "------- delete the img files of not used -------"
#rm ramdisk-recovery.img ramdisk.img

echo "------- split files : system.img userdata.img persist.img cache.img -------"
python checksparse.py -i rawprogram0.xml -o rawprogram_unsparse0.xml -s userdata.img -s super.img -s metadata.img
echo "------- system.img and userdata.img is too larger and not used, delete these -------"
rm super.img userdata.img metadata.img

echo "------- generate burn xml file -------"
rm rawprogram0.xml checksparse.py rawprogram*.xml.bak
cp rawprogram_unsparse0.xml rawprogram0.xml
rm rawprogram_unsparse0.xml
echo "------- exit $project -------"
cd ..

echo "-------- end to generate the packet of burning --------"

echo "------- zip $project firmware -------"
FILE_PATH=$project-$ver-$mode
mv $dir/ $FILE_PATH
zip -r $FILE_PATH.zip $FILE_PATH/
rm -rf $FILE_PATH

DATES=$(date +%m%d)

ln -sf $FILE_PATH.zip build-$DATES.zip

echo "------- end zip $project firmware -------"
