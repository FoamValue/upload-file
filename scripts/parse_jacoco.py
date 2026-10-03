#!/usr/bin/env python3
"""Parse jacoco.xml files and print per-module and per-class line/branch coverage."""
import re
import sys
import xml.etree.ElementTree as ET

MODULES = [
    "upload-file-core",
    "upload-file-servlet",
    "upload-file-servlet-jakarta",
    "upload-file-spring-boot-starter",
    "upload-file-spring-boot-starter-jakarta",
    "upload-file-store-jdbc",
    "upload-file-store-redis",
]

KEY_CLASSES = {
    "upload-file-core": [
        "cn/chenxinjie/uploadfile/core/service/ResumableUploadService",
        "cn/chenxinjie/uploadfile/core/service/TrustedUploadService",
        "cn/chenxinjie/uploadfile/core/store/TaskStoreQuotaStore",
    ],
    "upload-file-servlet": [
        "cn/chenxinjie/uploadfile/servlet/UploadFileContext",
    ],
    "upload-file-spring-boot-starter": [
        "cn/chenxinjie/uploadfile/springboot/UploadFileProperties",
    ],
}


def pct(missed, covered):
    total = missed + covered
    return 100.0 if total == 0 else 100.0 * covered / total


def class_coverage(root):
    out = {}
    for pkg in root.findall(".//package"):
        for cls in pkg.findall("class"):
            name = cls.get("name")
            if name.endswith("$"):
                continue
            line_missed = line_covered = 0
            branch_missed = branch_covered = 0
            for ctr in cls.findall("counter"):
                ctype = ctr.get("type")
                if ctype == "LINE":
                    line_missed = int(ctr.get("missed"))
                    line_covered = int(ctr.get("covered"))
                elif ctype == "BRANCH":
                    branch_missed = int(ctr.get("missed"))
                    branch_covered = int(ctr.get("covered"))
            out[name] = (line_missed, line_covered, branch_missed, branch_covered)
    return out


def module_totals(root):
    line_m = line_c = branch_m = branch_c = 0
    for pkg in root.findall(".//package"):
        for ctr in pkg.findall("counter"):
            ctype = ctr.get("type")
            if ctype == "LINE":
                line_m += int(ctr.get("missed"))
                line_c += int(ctr.get("covered"))
            elif ctype == "BRANCH":
                branch_m += int(ctr.get("missed"))
                branch_c += int(ctr.get("covered"))
    return line_m, line_c, branch_m, branch_c


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "all"
    print(f"{'Module':<34} {'Lines':>8} {'Line%':>7} {'Branches':>10} {'Branch%':>8}")
    print("-" * 72)
    for mod in MODULES:
        tree = ET.parse(f"{mod}/target/site/jacoco/jacoco.xml")
        root = tree.getroot()
        lm, lc, bm, bc = module_totals(root)
        print(f"{mod:<34} {lc:>4}/{lm+lc:<5} {pct(lm,lc):>6.1f}% {bc:>5}/{bm+bc:<6} {pct(bm,bc):>7.1f}%")

    if target == "classes" or target == "all":
        print()
        print(f"{'Class':<80} {'Lines':>8} {'Line%':>7} {'Branches':>10} {'Branch%':>8}")
        print("-" * 120)
        for mod, classes in KEY_CLASSES.items():
            tree = ET.parse(f"{mod}/target/site/jacoco/jacoco.xml")
            cc = class_coverage(tree.getroot())
            for wanted in classes:
                if wanted in cc:
                    lm, lc, bm, bc = cc[wanted]
                    short = wanted.rsplit("/", 1)[-1]
                    print(f"{mod}/{short:<58} {lc:>4}/{lm+lc:<5} {pct(lm,lc):>6.1f}% {bc:>5}/{bm+bc:<6} {pct(bm,bc):>7.1f}%")


if __name__ == "__main__":
    main()
