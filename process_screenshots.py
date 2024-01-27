import os
import argparse
from PIL import Image

# crop the system UI from the app screenshots using a predetermined percentage based on the device type
def crop_ui(image_path, output_path, top_crop_pct, bottom_crop_pct):
    with Image.open(image_path) as img:
        width, height = img.size
        # Estimate the size of the phone's UI
        top_bar_height = int(height * top_crop_pct)  # top 5% of the image
        bottom_bar_height = int(height * bottom_crop_pct)  # bottom 95% of the image

        # Crop the image to remove the phone's UI
        area_to_keep = (0, top_bar_height, width, bottom_bar_height)
        cropped_img = img.crop(area_to_keep)

        # Save the cropped image
        cropped_img.save(output_path)


# python process_screenshots.py --device_type phone --screenshot_path fastlane/metadata/android/fr-FR/images/phoneScreenshots
def main():
    parser = argparse.ArgumentParser(description='Process some images.')
    parser.add_argument('-d', '--device_type', type=str, choices=['phone', 'sevenInch', 'tenInch'],
                        help='The type of device (phone, sevenInch, tenInch)')
    parser.add_argument('-s', '--screenshot_path', type=str, help='Path to the folder containing screenshots')
    parser.add_argument('-o', '--optipng_path', type=str, help='Path to the optipng executable')

    args = parser.parse_args()

    if args.device_type == 'phone':
        top_pct, bottom_pct = 0.06, 0.95
    elif args.device_type == 'sevenInch':
        top_pct, bottom_pct = 0.03, 0.92
    elif args.device_type == 'tenInch':
        top_pct, bottom_pct = 0.025, 0.925

    # sort based on the filename so that they show up in order
    for image_filename in sorted(os.listdir(args.screenshot_path)):
        print(f'Cropping {image_filename} for {args.device_type}')

        input_path = os.path.join(args.screenshot_path, image_filename)

        # rename the file to just #.png
        output_filename = image_filename.split('-')[0] + '.png'
        output_path = os.path.join(args.screenshot_path, output_filename)

        crop_ui(input_path, output_path, top_pct, bottom_pct)

        print(f'Done cropping {input_path}, removing')
        os.remove(input_path)

        print(f'Optimizing png at {output_path}')

        if args.optipng_path:
            cmd = f'"{args.optipng_path}" -o7 "{output_path}"'
        else:
            cmd = f'optipng -o7 "{output_path}"'

        os.system(cmd)


if __name__ == "__main__":
    main()

