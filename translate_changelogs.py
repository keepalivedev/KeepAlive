import argparse

# pip install googletrans-py
from googletrans import Translator

def main():
    parser = argparse.ArgumentParser(description='Translate changelogs to the given locale(s)')
    parser.add_argument('-l', '--locale', type=str,  help='The locale to translate to')
    parser.add_argument('-v', '--version_code', type=str, help='Changelog version to translate')

    args = parser.parse_args()

    # language should be the first part of the locale
    language = args.locale.split('-')[0]

    # use english changelog as the source
    src_changelog_path = f'metadata/android/en-US/changelogs/{args.version_code}.txt'
    dest_changelog_path = f'metadata/android/{args.locale}/changelogs/{args.version_code}.txt'

    print(f'Translating {src_changelog_path} to {language} and saving to {dest_changelog_path}')

    with open(src_changelog_path, 'r') as _file:
        src_changelog_text = _file.read()

    translated_changelog_text = Translator().translate(src_changelog_text, dest=language).text
    print(translated_changelog_text)

    with open(dest_changelog_path, 'w') as _file:
        _file.write(translated_changelog_text)

    print(f'Changelog translated and saved to {dest_changelog_path}')


if __name__ == "__main__":
    main()

