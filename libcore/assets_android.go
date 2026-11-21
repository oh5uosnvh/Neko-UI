//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"

	"golang.org/x/mobile/asset"
)

func extractAssets() error {
	if intfNB4A == nil {
		return fmt.Errorf("asset provider is not initialized")
	}
	useOfficialAssets := intfNB4A.UseOfficialAssets()
	for _, name := range []string{geoipDat, geositeDat, yacdDstFolder} {
		if err := extractAssetName(name, useOfficialAssets); err != nil {
			return fmt.Errorf("extract %s: %w", name, err)
		}
	}
	return nil
}

// 这里解压的是 apk 里面的
func extractAssetName(name string, useOfficialAssets bool) error {
	// 支持非官方源的，就是 replaceable，放 Android 目录
	// 不支持非官方源的，就放 file 目录
	replaceable := true

	var version string
	var apkPrefix string
	switch name {
	case geoipDat:
		version = geoipVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeDat:
		version = geositeVersion
		apkPrefix = apkAssetPrefixSingBox
	case yacdDstFolder:
		version = yacdVersion
		replaceable = false
	}

	var dir string
	if !replaceable {
		dir = internalAssetsPath
	} else {
		dir = externalAssetsPath
	}
	dstName := filepath.Join(dir, name)

	var localVersion string
	var assetVersion string

	// loadAssetVersion from APK
	loadAssetVersion := func() error {
		av, err := asset.Open(apkPrefix + version)
		if err != nil {
			return fmt.Errorf("open version in assets: %v", err)
		}
		b, err := io.ReadAll(av)
		av.Close()
		if err != nil {
			return fmt.Errorf("read internal version: %v", err)
		}
		assetVersion = string(b)
		return nil
	}
	if err := loadAssetVersion(); err != nil {
		return err
	}

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets || !replaceable {
		// 官方源升级
		b, err := os.ReadFile(filepath.Join(dir, version))
		if err != nil {
			// versionFileMissing
			doExtract = true
			_ = os.Remove(filepath.Join(dir, version))
		} else {
			localVersion = string(b)
			if localVersion == "Custom" {
				doExtract = false
			} else {
				av, err := strconv.ParseUint(assetVersion, 10, 64)
				if err != nil {
					doExtract = assetVersion != localVersion
				} else {
					lv, err := strconv.ParseUint(localVersion, 10, 64)
					doExtract = err != nil || av > lv
				}
			}
		}
	} else {
		//非官方源不升级
	}

	if !doExtract {
		return nil
	}

	extractXz := func(f asset.File) error {
		tmpXzName := dstName + ".xz"
		err := extractAsset(f, tmpXzName)
		if err == nil {
			temporary := dstName + ".tmp"
			err = Unxz(tmpXzName, temporary)
			if err == nil {
				err = os.Rename(temporary, dstName)
			} else {
				_ = os.Remove(temporary)
			}
			os.Remove(tmpXzName)
		}
		if err != nil {
			return fmt.Errorf("extract xz: %v", err)
		}
		return nil
	}

	extracZip := func(f asset.File, outDir string) error {
		tmpZipName := dstName + ".zip"
		err := extractAsset(f, tmpZipName)
		if err == nil {
			err = Unzip(tmpZipName, outDir)
			os.Remove(tmpZipName)
		}
		if err != nil {
			return fmt.Errorf("extract zip: %v", err)
		}
		return nil
	}

	if f, err := asset.Open(apkPrefix + name + ".xz"); err == nil {
		if err := extractXz(f); err != nil {
			return err
		}
	} else if name == yacdDstFolder {
		f, err := asset.Open("yacd.zip")
		if err != nil {
			return err
		}
		os.RemoveAll(dstName)
		if err := extracZip(f, internalAssetsPath); err != nil {
			return err
		}
		m, err := filepath.Glob(internalAssetsPath + "/Yacd-*")
		if err != nil {
			return fmt.Errorf("glob Yacd: %v", err)
		}
		if len(m) != 1 {
			return fmt.Errorf("glob Yacd found %d result, expect 1", len(m))
		}
		err = os.Rename(m[0], dstName)
		if err != nil {
			return fmt.Errorf("rename Yacd: %v", err)
		}

	} else {
		return fmt.Errorf("asset %s.xz unavailable", name)
	}

	o, err := os.Create(filepath.Join(dir, version))
	if err != nil {
		return fmt.Errorf("create version: %v", err)
	}
	_, err = io.WriteString(o, assetVersion)
	o.Close()
	return err
}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer o.Close()
	_, err = io.Copy(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}
